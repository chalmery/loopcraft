package io.github.chalmery.loopcraft.identity.service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.chalmery.loopcraft.identity.dto.IdentityViews;
import io.github.chalmery.loopcraft.identity.mapper.TenantMapper;
import io.github.chalmery.loopcraft.identity.mapper.TenantMemberMapper;
import io.github.chalmery.loopcraft.identity.mapper.UserMapper;
import io.github.chalmery.loopcraft.identity.model.SessionUser;
import io.github.chalmery.loopcraft.identity.model.Tenant;
import io.github.chalmery.loopcraft.identity.model.TenantMember;
import io.github.chalmery.loopcraft.identity.model.TenantRole;
import io.github.chalmery.loopcraft.identity.model.UserAccount;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 账号认证与租户成员管理，平台权限和租户角色分开校验。 */
@Service
public class IdentityService {
    private final ObjectProvider<UserMapper> userMappers;
    private final ObjectProvider<TenantMapper> tenantMappers;
    private final ObjectProvider<TenantMemberMapper> memberMappers;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    public IdentityService(ObjectProvider<UserMapper> userMappers, ObjectProvider<TenantMapper> tenantMappers,
            ObjectProvider<TenantMemberMapper> memberMappers, PasswordEncoder passwords) {
        this.userMappers = userMappers;
        this.tenantMappers = tenantMappers;
        this.memberMappers = memberMappers;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    private UserMapper users() {
        UserMapper mapper = userMappers.getIfAvailable();
        if (mapper == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "账号数据库不可用");
        }
        return mapper;
    }

    public UserAccount login(String username, String password) {
        UserAccount user = users().selectOne(Wrappers.<UserAccount>lambdaQuery()
                .eq(UserAccount::getUsername, username.toLowerCase(Locale.ROOT)));
        boolean matches = passwords.matches(password, user == null ? dummyHash : user.getPasswordHash());
        if (!matches || user == null || !user.getEnabled()) {
            throw new BadCredentialsException("用户名或密码错误");
        }
        return user;
    }

    public UserAccount current(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SessionUser session)) {
            throw new BadCredentialsException("请先登录");
        }
        UserAccount user = users().selectById(session.userId());
        if (user == null || !user.getEnabled() || user.getCredentialVersion() != session.credentialVersion()) {
            throw new BadCredentialsException("登录已失效，请重新登录");
        }
        return user;
    }

    private void requireAdmin(UserAccount actor) {
        if (!actor.getPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要平台管理员权限");
        }
    }

    public List<UserAccount> users(UserAccount actor, int page, int size) {
        requireAdmin(actor);
        return users().selectPage(new Page<>(page, size, false),
                Wrappers.<UserAccount>lambdaQuery().orderByAsc(UserAccount::getId)).getRecords();
    }

    public IdentityViews.PageResult<IdentityViews.User> userPage(UserAccount actor, int page, int size) {
        requireAdmin(actor);
        Page<UserAccount> result = users().selectPage(new Page<>(page, size),
                Wrappers.<UserAccount>lambdaQuery().orderByAsc(UserAccount::getId));
        return new IdentityViews.PageResult<>(result.getRecords().stream().map(IdentityViews.User::of).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Transactional
    public UserAccount updateUser(UserAccount actor, long id, String username, String displayName, boolean enabled) {
        requireAdmin(actor);
        // 加锁后再比较账号状态，避免依据旧值遗漏凭证版本变更。
        UserAccount target = lockUser(id);
        if (target.getPlatformAdmin() && !enabled) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能停用平台管理员");
        }
        String normalized = username.toLowerCase(Locale.ROOT);
        var update = Wrappers.<UserAccount>lambdaUpdate().eq(UserAccount::getId, id)
                .set(UserAccount::getUsername, normalized).set(UserAccount::getDisplayName, displayName.strip())
                .set(UserAccount::getEnabled, enabled).set(UserAccount::getUpdatedAt, OffsetDateTime.now());
        if (target.getEnabled() != enabled || !target.getUsername().equals(normalized)) {
            update.setIncrBy(UserAccount::getCredentialVersion, 1);
        }
        users().update(update);
        return user(id);
    }

    /** 删除普通账号及其成员关系；负责人必须先完成交接。 */
    @Transactional
    public void deleteUser(UserAccount actor, long id) {
        requireAdmin(actor);
        if (lockUser(id).getPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能删除平台管理员");
        }
        if (memberMappers.getObject().selectCount(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getUserId, id).eq(TenantMember::getRole, TenantRole.OWNER)) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该账号是租户负责人，请先在租户管理中更换负责人");
        }
        memberMappers.getObject().delete(Wrappers.<TenantMember>lambdaQuery().eq(TenantMember::getUserId, id));
        users().deleteById(id);
    }

    public UserAccount createUser(UserAccount actor, String username, String password, String name) {
        requireAdmin(actor);
        UserAccount user = new UserAccount(null, username.toLowerCase(Locale.ROOT), passwords.encode(password),
                name.strip(), false, true, 0);
        users().insert(user);
        return user;
    }

    public void setEnabled(UserAccount actor, long id, boolean enabled) {
        requireAdmin(actor);
        if (user(id).getPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "首版不支持停用平台管理员");
        }
        users().update(Wrappers.<UserAccount>lambdaUpdate().eq(UserAccount::getId, id)
                .set(UserAccount::getEnabled, enabled).setIncrBy(UserAccount::getCredentialVersion, 1)
                .set(UserAccount::getUpdatedAt, OffsetDateTime.now()));
    }

    public UserAccount rename(UserAccount actor, String name) {
        users().update(Wrappers.<UserAccount>lambdaUpdate().eq(UserAccount::getId, actor.getId())
                .set(UserAccount::getDisplayName, name.strip()).set(UserAccount::getUpdatedAt, OffsetDateTime.now()));
        return user(actor.getId());
    }

    public void changePassword(UserAccount actor, String oldPassword, String newPassword) {
        if (!passwords.matches(oldPassword, actor.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前密码错误");
        }
        int changed = users().update(Wrappers.<UserAccount>lambdaUpdate()
                .eq(UserAccount::getId, actor.getId()).eq(UserAccount::getCredentialVersion, actor.getCredentialVersion())
                .set(UserAccount::getPasswordHash, passwords.encode(newPassword))
                .setIncrBy(UserAccount::getCredentialVersion, 1).set(UserAccount::getUpdatedAt, OffsetDateTime.now()));
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已变更，请重新登录");
        }
    }

    /** 只初始化空库，重启不会覆盖密码或提升已有账号权限。 */
    @Transactional
    public void bootstrap(String username, String password) {
        users().lockBootstrap();
        if (users().selectCount(null) != 0) {
            return;
        }
        users().insert(new UserAccount(null, username.toLowerCase(Locale.ROOT), passwords.encode(password),
                "管理员", true, true, 0));
    }

    private UserAccount user(long id) {
        UserAccount user = users().selectById(id);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return user;
    }

    /** 避免删除账号与任命负责人交错。 */
    private UserAccount lockUser(long id) {
        if (users().lockById(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        }
        return user(id);
    }

    @Transactional
    public Tenant createTenant(UserAccount actor, String name, long ownerId) {
        requireAdmin(actor);
        if (!lockUser(ownerId).getEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "负责人账号已停用");
        }
        Tenant tenant = new Tenant();
        tenant.setName(name.strip());
        tenantMappers.getObject().insert(tenant);
        TenantMember member = new TenantMember();
        member.setTenantId(tenant.getId());
        member.setUserId(ownerId);
        member.setRole(TenantRole.OWNER);
        memberMappers.getObject().insert(member);
        return tenant;
    }

    public List<Tenant> tenants(UserAccount actor, int page, int size) {
        if (actor.getPlatformAdmin()) {
            return tenantMappers.getObject().selectPage(new Page<>(page, size, false),
                    Wrappers.<Tenant>lambdaQuery().orderByAsc(Tenant::getId)).getRecords();
        }
        List<Long> tenantIds = memberMappers.getObject().selectPage(new Page<>(page, size, false),
                Wrappers.<TenantMember>lambdaQuery().eq(TenantMember::getUserId, actor.getId())
                        .orderByAsc(TenantMember::getTenantId)).getRecords()
                .stream().map(TenantMember::getTenantId).toList();
        if (tenantIds.isEmpty()) {
            return List.of();
        }
        return tenantMappers.getObject().selectList(Wrappers.<Tenant>lambdaQuery()
                .in(Tenant::getId, tenantIds).orderByAsc(Tenant::getId));
    }

    public List<IdentityViews.AccessibleTenant> accessibleTenants(UserAccount actor, int page, int size) {
        List<Tenant> list = tenants(actor, page, size);
        if (list.isEmpty()) return List.of();
        Map<Long, TenantRole> roles = memberMappers.getObject().selectList(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getUserId, actor.getId())
                .in(TenantMember::getTenantId, list.stream().map(Tenant::getId).toList())).stream()
                .collect(Collectors.toMap(TenantMember::getTenantId, TenantMember::getRole));
        return list.stream().map(item -> new IdentityViews.AccessibleTenant(item.getId(), item.getName(),
                item.getUpdatedAt(), roles.get(item.getId()))).toList();
    }

    /** 多次批量查询共用快照，避免交接或删除导致负责人信息前后不一致。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IdentityViews.PageResult<IdentityViews.ManagedTenant> tenantPage(UserAccount actor, int page, int size) {
        requireAdmin(actor);
        Page<Tenant> result = tenantMappers.getObject().selectPage(new Page<>(page, size),
                Wrappers.<Tenant>lambdaQuery().orderByAsc(Tenant::getId));
        List<Tenant> list = result.getRecords();
        if (list.isEmpty()) return new IdentityViews.PageResult<>(List.of(), result.getTotal(), page, size);
        Map<Long, Long> owners = memberMappers.getObject().selectList(Wrappers.<TenantMember>lambdaQuery()
                .in(TenantMember::getTenantId, list.stream().map(Tenant::getId).toList())
                .eq(TenantMember::getRole, TenantRole.OWNER)).stream()
                .collect(Collectors.toMap(TenantMember::getTenantId, TenantMember::getUserId));
        if (owners.size() != list.size()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "租户负责人关系异常");
        }
        Map<Long, UserAccount> accounts = users().selectByIds(owners.values()).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        return new IdentityViews.PageResult<>(list.stream().map(item -> {
            UserAccount owner = accounts.get(owners.get(item.getId()));
            if (owner == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "租户负责人账号不存在");
            }
            return new IdentityViews.ManagedTenant(item.getId(), item.getName(), owner.getId(), owner.getUsername(),
                    owner.getDisplayName(), item.getUpdatedAt());
        }).toList(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    /** 先锁租户再锁账号，与成员变更共用锁顺序。 */
    @Transactional
    public Tenant updateTenant(UserAccount actor, long id, String name, long ownerId) {
        requireAdmin(actor);
        lockTenant(id);
        if (!lockUser(ownerId).getEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "负责人账号已停用");
        }
        int changed = tenantMappers.getObject().update(Wrappers.<Tenant>lambdaUpdate().eq(Tenant::getId, id)
                .set(Tenant::getName, name.strip()).set(Tenant::getUpdatedAt, OffsetDateTime.now()));
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        TenantMember owner = memberMappers.getObject().selectOne(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, id).eq(TenantMember::getRole, TenantRole.OWNER));
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "租户负责人关系异常");
        }
        if (owner.getUserId() != ownerId) {
            int demoted = memberMappers.getObject().update(Wrappers.<TenantMember>lambdaUpdate()
                    .eq(TenantMember::getId, owner.getId()).eq(TenantMember::getRole, TenantRole.OWNER)
                    .set(TenantMember::getRole, TenantRole.MEMBER));
            if (demoted != 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "租户负责人已变更，请重试");
            }
            TenantMember next = memberMappers.getObject().selectOne(Wrappers.<TenantMember>lambdaQuery()
                    .eq(TenantMember::getTenantId, id).eq(TenantMember::getUserId, ownerId));
            if (next == null) {
                next = new TenantMember();
                next.setTenantId(id);
                next.setUserId(ownerId);
                next.setRole(TenantRole.OWNER);
                memberMappers.getObject().insert(next);
            } else {
                int promoted = memberMappers.getObject().update(Wrappers.<TenantMember>lambdaUpdate()
                        .eq(TenantMember::getId, next.getId()).eq(TenantMember::getRole, TenantRole.MEMBER)
                        .set(TenantMember::getRole, TenantRole.OWNER));
                if (promoted != 1) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "接任人的成员关系已变更，请重试");
                }
            }
        }
        return tenantMappers.getObject().selectById(id);
    }

    /** 外键保护尚有关联业务数据的租户；删除失败时成员关系一并回滚。 */
    @Transactional
    public void deleteTenant(UserAccount actor, long id) {
        requireAdmin(actor);
        lockTenant(id);
        memberMappers.getObject().delete(Wrappers.<TenantMember>lambdaQuery().eq(TenantMember::getTenantId, id));
        try {
            tenantMappers.getObject().deleteById(id);
        } catch (DataIntegrityViolationException error) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "租户仍有关联业务数据，请先清理关联记录");
        }
    }

    private void lockTenant(long id) {
        if (tenantMappers.getObject().lockById(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
    }

    /** 工作区与成员页面仅向当前租户负责人开放。 */
    private void requireOwner(UserAccount actor, long tenantId) {
        Long count = memberMappers.getObject().selectCount(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenantId).eq(TenantMember::getUserId, actor.getId())
                .eq(TenantMember::getRole, TenantRole.OWNER));
        if (count == 0) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要当前租户负责人权限");
    }

    public Tenant tenant(UserAccount actor, long tenantId, boolean manage) {
        TenantMember member = memberMappers.getObject().selectOne(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenantId).eq(TenantMember::getUserId, actor.getId()));
        if (!actor.getPlatformAdmin() && (member == null || (manage && member.getRole() != TenantRole.OWNER))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无此租户的访问权限");
        }
        Tenant tenant = tenantMappers.getObject().selectById(tenantId);
        if (tenant == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
        }
        return tenant;
    }

    @Transactional
    public Tenant renameTenant(UserAccount actor, long tenantId, String name) {
        lockTenant(tenantId);
        requireOwner(actor, tenantId);
        tenantMappers.getObject().update(Wrappers.<Tenant>lambdaUpdate().eq(Tenant::getId, tenantId)
                .set(Tenant::getName, name.strip()).set(Tenant::getUpdatedAt, OffsetDateTime.now()));
        return tenantMappers.getObject().selectById(tenantId);
    }

    public List<IdentityViews.Member> members(UserAccount actor, long tenantId, int page, int size) {
        requireOwner(actor, tenantId);
        List<TenantMember> members = memberMappers.getObject().selectPage(new Page<>(page, size, false),
                Wrappers.<TenantMember>lambdaQuery().eq(TenantMember::getTenantId, tenantId)
                        .orderByAsc(TenantMember::getUserId)).getRecords();
        if (members.isEmpty()) {
            return List.of();
        }
        // 批量查询用户后组装成员信息，避免逐个查询和额外的联表 SQL。
        List<Long> userIds = members.stream().map(TenantMember::getUserId).toList();
        Map<Long, UserAccount> accounts = users().selectList(Wrappers.<UserAccount>lambdaQuery()
                .in(UserAccount::getId, userIds)).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        return members.stream().map(member -> {
            UserAccount user = accounts.get(member.getUserId());
            return new IdentityViews.Member(user.getId(), user.getUsername(), user.getDisplayName(),
                    member.getRole(), user.getEnabled());
        }).toList();
    }

    /** 查询尚未加入当前租户的启用账号，仅租户负责人可见。 */
    public List<IdentityViews.MemberCandidate> memberCandidates(UserAccount actor, long tenantId, int page, int size) {
        requireOwner(actor, tenantId);
        List<Long> memberIds = memberMappers.getObject().selectList(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenantId)).stream().map(TenantMember::getUserId).toList();
        var query = Wrappers.<UserAccount>lambdaQuery().eq(UserAccount::getEnabled, true)
                .orderByAsc(UserAccount::getId);
        if (!memberIds.isEmpty()) {
            query.notIn(UserAccount::getId, memberIds);
        }
        return users().selectPage(new Page<>(page, size, false), query).getRecords().stream()
                .map(IdentityViews.MemberCandidate::of).toList();
    }

    @Transactional
    public void addMember(UserAccount actor, long tenantId, long userId) {
        lockTenant(tenantId);
        requireOwner(actor, tenantId);
        if (!lockUser(userId).getEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户已停用");
        }
        TenantMember member = new TenantMember();
        member.setTenantId(tenantId);
        member.setUserId(userId);
        member.setRole(TenantRole.MEMBER);
        memberMappers.getObject().insert(member);
    }

    @Transactional
    public void removeMember(UserAccount actor, long tenantId, long userId) {
        lockTenant(tenantId);
        requireOwner(actor, tenantId);
        int removed = memberMappers.getObject().delete(Wrappers.<TenantMember>lambdaQuery()
                .eq(TenantMember::getTenantId, tenantId).eq(TenantMember::getUserId, userId)
                .eq(TenantMember::getRole, TenantRole.MEMBER));
        if (removed != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "成员不存在或是租户负责人，无法移除");
        }
    }
}
