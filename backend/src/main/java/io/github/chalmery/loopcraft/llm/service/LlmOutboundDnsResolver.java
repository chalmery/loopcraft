package io.github.chalmery.loopcraft.llm.service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 在建立连接时校验全部解析结果，连接直接使用这些地址，不再二次解析。 */
@Component
public class LlmOutboundDnsResolver implements DnsResolver {
    private final Set<String> allowedInternalHosts;
    private final DnsResolver delegate;

    @org.springframework.beans.factory.annotation.Autowired
    public LlmOutboundDnsResolver(@Value("${loopcraft.llm.discovery-allowed-internal-hosts:}") String allowedInternalHosts) {
        this(allowedInternalHosts, SystemDefaultDnsResolver.INSTANCE);
    }

    LlmOutboundDnsResolver(String allowedInternalHosts, DnsResolver delegate) {
        this.allowedInternalHosts = Arrays.stream(allowedInternalHosts.split(","))
                .map(String::strip).filter(s -> !s.isEmpty()).map(LlmOutboundDnsResolver::normalize)
                .collect(Collectors.toUnmodifiableSet());
        this.delegate = delegate;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = delegate.resolve(host);
        if (addresses.length == 0 || (!allowedInternalHosts.contains(normalize(host))
                && Arrays.stream(addresses).anyMatch(address -> !isPublic(address)))) {
            throw new UnknownHostException("供应商地址受出站策略限制");
        }
        return addresses;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        resolve(host);
        return host;
    }

    private static String normalize(String host) {
        return host.toLowerCase(Locale.ROOT).replaceAll("^\\[|\\]$|\\.$", "");
    }

    private static boolean isPublic(InetAddress address) {
        byte[] bytes = address.getAddress();
        int a = Byte.toUnsignedInt(bytes[0]);
        int b = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 16) {
            // 仅全球单播；额外排除协议分配、文档和可嵌入 IPv4 的 6to4 地址。
            return (a & 0xe0) == 0x20
                    && !(a == 0x20 && b == 1 && (Byte.toUnsignedInt(bytes[2]) < 2
                        || (Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8)))
                    && !(a == 0x20 && b == 2)
                    && !(a == 0x3f && b == 0xff && (Byte.toUnsignedInt(bytes[2]) & 0xf0) == 0);
        }
        int c = Byte.toUnsignedInt(bytes[2]);
        return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && b >= 64 && b <= 127)
                && !(a == 169 && b == 254)
                && !(a == 172 && b >= 16 && b <= 31)
                && !(a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2))))
                && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                && !(a == 203 && b == 0 && c == 113)
                && !(a == 168 && b == 63 && c == 129 && Byte.toUnsignedInt(bytes[3]) == 16);
    }
}
