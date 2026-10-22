const $=id=>document.getElementById(id)
const clone=x=>structuredClone(x)
function sample(slug,name,description,tags){return {slug,name,description,tags,enabled:true,origin:'CUSTOM',versions:[{number:1,createdAt:'2026-10-21 09:30',files:{'SKILL.md':`---\nname: package-example\ndescription: 包内描述，仅作为文件内容保存。\n---\n\n# ${name}\n\n## 适用场景\n\n${description}\n\n## 工作步骤\n\n1. 确认任务目标与输入材料。\n2. 阅读 references/guide.md。\n3. 整理结果并核查。\n`,'references/guide.md':'# 参考规范\n\n保留来源，对未核实的内容明确标注。\n','scripts/check.py':'# 示例脚本，原型不执行\ndef check(text):\n    return bool(text.strip())\n'}}]}}
const data={a:[sample('document-review','文档审阅','审阅文档结构、术语和表达，给出可执行的修改建议。',['文档','质量检查']),sample('report-writing','报告撰写','从输入材料中提炼要点，整理成结构清晰的业务报告。',['写作','报告']),sample('meeting-notes','会议纪要','提取会议结论、待办事项与负责人。',['协作']),sample('data-analysis','数据分析','梳理数据口径，识别异常并解释变化。',['分析'])],b:[sample('knowledge-organizer','知识整理','将零散资料归纳为可检索的知识条目。',['知识'])]}
data.a[0].versions.push({...clone(data.a[0].versions[0]),number:2,createdAt:'2026-10-22 10:30'});data.a[0].versions[1].files['SKILL.md']+='\n4. 按影响程度整理修改建议。\n'
data.a[0].origin='SYSTEM';
let tagDraft=[];
let tenant='a',current=null,versionIndex=0,draft=null,isNew=false,path='SKILL.md',metaDirty=false,pending=null,importing=false,zipReady=false,timer
const read=()=>$('mode').value==='read'||(current?.origin==='SYSTEM'&&!['owner','admin'].includes($('mode').value))
const version=()=>current.versions[versionIndex]
const dirty=()=>!!draft||metaDirty
function toast(message){$('toast').textContent=message;$('toast').hidden=false;clearTimeout(timer);timer=setTimeout(()=>$('toast').hidden=true,3000)}
function guard(action){if(!dirty()){action();return}pending=action;$('leave-dialog').showModal()}
function element(tag,text,cls){const e=document.createElement(tag);e.textContent=text;if(cls)e.className=cls;return e}
function list(){current=null;draft=null;isNew=false;metaDirty=false;$('listing').hidden=false;$('detail').hidden=true;$('breadcrumb').textContent='工作区 / Skill 管理';renderCards()}
function renderCards(){const q=$('search').value.trim().toLowerCase();const items=data[tenant].filter(s=>`${s.name} ${s.slug} ${s.description} ${s.tags.join(' ')}`.toLowerCase().includes(q));$('cards').replaceChildren();$('count').textContent=`${items.length} 个 Skill`;$('empty').hidden=items.length>0;$('create').disabled=$('mode').value==='read';$('import').disabled=$('mode').value==='read';for(const s of items){const card=element('article','','card'),top=element('div','','card-top');const title=element('div','','card-title');title.append(element('h2',s.name),element('div',s.slug,'slug'));top.append(title,element('span',`v${s.versions.at(-1).number}`,'badge'));if(s.origin==='SYSTEM')top.append(element('span','内置','badge'));if(!s.enabled)top.append(element('span','已禁用','badge'));card.append(top,element('p',s.description,'card-description'));const tags=element('div','','tags');s.tags.forEach(t=>tags.append(element('span',t,'tag')));card.append(tags);const bottom=element('div','','card-bottom');bottom.append(element('span',`${s.versions.length} 个版本 · ${Object.keys(s.versions.at(-1).files).length} 个文件`));const btn=element('button','查看详情','secondary');btn.setAttribute('aria-label',`查看 ${s.name} 详情`);btn.onclick=()=>open(s);bottom.append(btn);card.append(bottom);$('cards').append(card)}}
function open(s){current=s;versionIndex=s.versions.length-1;isNew=false;draft=null;metaDirty=false;showDetail('info')}
function showDetail(tab){$('listing').hidden=true;$('detail').hidden=false;$('breadcrumb').textContent='工作区 / Skill 管理 / 详情';$('detail-name').textContent=current.name;$('detail-slug').textContent=current.slug;$('latest').textContent=isNew?'尚未创建':`最新 v${current.versions.at(-1).number}`;$('meta-name').value=current.name;$('meta-slug').value=current.slug;$('meta-description').value=current.description;$('meta-origin').value=current.origin==='SYSTEM'?'系统内置':'租户自建';$('meta-enabled').value=String(current.enabled);$('meta-enabled').disabled=read()||isNew;tagDraft=[...current.tags];$('tag-input').value='';renderTagEditor();['meta-name','meta-description'].forEach(id=>$(id).readOnly=read()||isNew);$('save-meta').disabled=read()||isNew;$('meta-error').textContent='';switchTab(tab);renderVersions();renderVersionList()}
function switchTab(tab){$('info-panel').hidden=tab!=='info';$('content-panel').hidden=tab!=='content';$('tab-info').classList.toggle('selected',tab==='info');$('tab-content').classList.toggle('selected',tab==='content')}
function renderVersions(){const select=$('version');select.replaceChildren();if(isNew){select.append(new Option('v1 · 待保存','0'))}else current.versions.forEach((v,i)=>select.append(new Option(`v${v.number}`,String(i))));select.value=String(versionIndex);select.disabled=!!draft;$('edit-content').hidden=!!draft||(!isNew&&versionIndex!==current.versions.length-1);$('edit-content').disabled=read();$('save-version').hidden=!draft;$('save-version').textContent=isNew?'保存并创建 v1':`保存为 v${current.versions.at(-1).number+1}`;$('cancel-edit').hidden=!draft;$('new-file').hidden=!draft;$('version-note').textContent=draft?'正在编辑 · 保存后生成新版本':versionIndex===current.versions.length-1?'最新版本':'历史版本 · 仅查看';$('snapshot').textContent=`当前 Skill：${current.slug}`;$('version-error').textContent='';renderFiles()}
function renderVersionList(){
  $('version-count').textContent=`${current.versions.length} 个版本`;$('version-rows').replaceChildren();$('no-versions').hidden=current.versions.length>0
  current.versions.map((v,index)=>({v,index})).reverse().forEach(({v,index})=>{
    const row=element('tr'),name=element('td',`v${v.number}`)
    if(index===current.versions.length-1)name.append(element('span','最新','badge'))
    row.append(name,element('td',String(Object.keys(v.files).length)),element('td',v.createdAt))
    const action=element('td','','action-cell'),button=element('button','查看详情','version-link')
    button.setAttribute('aria-label',`查看 v${v.number} 内容详情`)
    button.onclick=()=>guard(()=>{metaDirty=false;draft=null;versionIndex=index;path='SKILL.md';showDetail('content');$('tab-content').focus()})
    action.append(button);row.append(action);$('version-rows').append(row)
  })
}
function renderFiles(){const files=draft||version().files;if(!(path in files))path='SKILL.md';$('files').replaceChildren();Object.keys(files).forEach(p=>{const b=element('button',p,'file'+(p===path?' selected':''));b.onclick=()=>{path=p;renderFiles()};$('files').append(b)});$('filepath').textContent=path;$('body').value=files[path]??'';$('body').readOnly=!draft;$('body').hidden=files[path]===null;$('binary').hidden=files[path]!==null;$('edit-state').textContent=draft?'编辑中':'只读';$('file-hint').textContent=path==='SKILL.md'?'SKILL.md 按文件原文编辑，包内字段不与平台基本信息同步。':'此文件随整个包一起生成版本。'}
$('body').oninput=()=>{if(draft)draft[path]=$('body').value}
$('edit-content').onclick=()=>{if(read()||versionIndex!==current.versions.length-1)return;draft=clone(version().files);renderVersions()}
$('cancel-edit').onclick=()=>guard(()=>{if(isNew)list();else{draft=null;renderVersions()}})
$('save-version').onclick=()=>{if(read()||!draft)return;if(!draft['SKILL.md']?.trim()){$('version-error').textContent='SKILL.md 正文不能为空。';return}if($('fail').checked){$('version-error').textContent='模拟保存失败，编辑内容已保留；没有创建新版本。';return}if(isNew&&data[tenant].some(s=>s.slug===current.slug)){$('version-error').textContent='此租户已存在相同标识。';return}const v={createdAt:new Date().toLocaleString('sv-SE',{timeZone:'Asia/Shanghai'}).slice(0,16),number:isNew?1:current.versions.at(-1).number+1,files:clone(draft)};current.versions.push(v);if(isNew)data[tenant].push(current);isNew=false;draft=null;versionIndex=current.versions.length-1;showDetail('content');toast(`v${v.number} 已保存到原型内存，历史版本保持不变`)}
$('version').onchange=()=>{versionIndex=Number($('version').value);path='SKILL.md';renderVersions()}
$('tab-info').onclick=()=>guard(()=>{if(isNew){toast('请先保存首个内容版本，或取消创建');return}draft=null;metaDirty=false;showDetail('info')})
$('tab-content').onclick=()=>{if(draft){switchTab('content');return}guard(()=>{metaDirty=false;showDetail('content')})}
;['meta-name','meta-description','meta-enabled'].forEach(id=>$(id).oninput=()=>metaDirty=true)
function renderTagEditor(){
  $('tag-chips').replaceChildren();$('tag-input').disabled=read()||isNew;$('add-tag').disabled=read()||isNew
  tagDraft.forEach((tag,index)=>{const chip=element('span',tag,'tag');if(!read()&&!isNew){const remove=element('button','×','remove-tag');remove.type='button';remove.setAttribute('aria-label',`移除标签 ${tag}`);remove.onclick=()=>{tagDraft.splice(index,1);metaDirty=true;renderTagEditor()};chip.append(remove)}$('tag-chips').append(chip)})
}
function addTag(){const text=$('tag-input').value.trim();if(!text||read()||isNew)return;if(!tagDraft.includes(text))tagDraft.push(text);$('tag-input').value='';metaDirty=true;renderTagEditor();$('tag-input').focus()}
$('tag-input').onkeydown=e=>{if(e.key==='Enter'&&!e.isComposing){e.preventDefault();addTag()}}
$('tag-input').oninput=()=>metaDirty=true
$('add-tag').onclick=addTag
$('save-meta').onclick=()=>{if(read())return;const name=$('meta-name').value.trim(),description=$('meta-description').value.trim();if(!name||!description){$('meta-error').textContent='名称和描述不能为空。';return}if($('fail').checked){$('meta-error').textContent='模拟保存失败，输入内容已保留。';return}Object.assign(current,{name,description,enabled:$('meta-enabled').value==='true',tags:[...new Set([...tagDraft,$('tag-input').value.trim()].filter(Boolean))]});metaDirty=false;showDetail('info');toast('基本信息已保存，内容版本数量不变')}
function newDialog(upload){importing=upload;zipReady=false;$('new-form').reset();$('zip-status').textContent='';$('new-error').textContent='';$('upload-area').hidden=!upload;$('new-title').textContent=upload?'上传 Skill 压缩包':'在线新增 Skill';$('submit-new').textContent=upload?'载入内容草稿（模拟）':'创建内容草稿';$('new-dialog').showModal()}
$('create').onclick=()=>newDialog(false);$('import').onclick=()=>newDialog(true);$('close-new').onclick=()=>$('new-dialog').close()
$('zip').onchange=()=>{$('zip-status').textContent=$('zip').files[0]?`已选择 ${$('zip').files[0].name}；请载入示例包演示解析结果。`:'';zipReady=false}
$('sample-zip').onclick=()=>{zipReady=true;$('zip-status').textContent='模拟解析完成：SKILL.md、references/guide.md、scripts/check.py，共 3 个文件。'}
$('new-form').onsubmit=e=>{e.preventDefault();if(importing&&!zipReady){$('new-error').textContent='请先载入示例包以演示解析步骤。';return}const slug=$('new-slug').value.trim(),name=$('new-name').value.trim(),description=$('new-description').value.trim();if(!name||!description){$('new-error').textContent='名称和描述不能为空。';return}if(data[tenant].some(s=>s.slug===slug)){$('new-error').textContent='当前租户已有相同 Skill 标识。';return}const s=sample(slug,name,description,[]);draft=importing?clone(s.versions[0].files):{'SKILL.md':`# ${name}\n\n## 工作步骤\n\n请填写 Skill 指令。\n`};current={...s,versions:[]};isNew=true;versionIndex=0;path='SKILL.md';metaDirty=false;$('new-dialog').close();showDetail('content')}
$('new-file').onclick=()=>{$('file-form').reset();$('path-error').textContent='';$('file-dialog').showModal()};$('close-file').onclick=()=>$('file-dialog').close()
$('file-form').onsubmit=e=>{e.preventDefault();const p=$('file-path').value.trim();if(!p||p.startsWith('/')||p.includes('\\')||p.split('/').some(x=>!x||x==='.'||x==='..')||Object.hasOwn(draft,p)){$('path-error').textContent='请输入不重复的包内相对路径，不能包含 .. 或空目录。';return}draft[p]='';path=p;$('file-dialog').close();renderFiles()}
$('back').onclick=()=>guard(list);$('search').oninput=renderCards
$('tenant').onchange=()=>{const next=$('tenant').value;$('tenant').value=tenant;guard(()=>{tenant=next;$('tenant').value=tenant;$('search').value='';list()})}
let mode='edit';$('mode').onchange=()=>{const next=$('mode').value;$('mode').value=mode;guard(()=>{mode=next;$('mode').value=mode;draft=null;metaDirty=false;if(isNew)list();else if(current)showDetail('info');else renderCards()})}
$('stay').onclick=()=>{pending=null;$('leave-dialog').close()};$('leave').onclick=()=>{$('leave-dialog').close();const fn=pending;pending=null;fn?.()};$('leave-dialog').onclose=()=>{pending=null}
window.addEventListener('beforeunload',e=>{if(dirty()){e.preventDefault();e.returnValue=''}});list()
