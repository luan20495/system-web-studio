
(function(){
"use strict";
var STORAGE_KEY="system-web-studio-state-v3";
var publishChoice="private";
var undoStack=[];
var redoStack=[];
var editingProductId=null;

var templateContent={
 heroEyebrow:"Pure living • smart water",
 heroTitle:"Nước sạch mỗi ngày, sống khỏe mỗi ngày.",
 heroDescription:"Giải pháp lọc nước hiện đại cho gia đình Việt — thiết kế tinh gọn, vận hành thông minh và trải nghiệm chăm sóc an tâm.",
 showComparison:false,
 showTestimonials:true,
 products:[
  {id:"p1",name:"K-Series Pure",description:"Thiết kế tối giản • 10 lõi lọc • Gia đình 2–4 người"},
  {id:"p2",name:"Smart RO Max",description:"RO thông minh • Theo dõi chất lượng nước"},
  {id:"p3",name:"Eco Compact",description:"Nhỏ gọn • Tiết kiệm điện • Không gian hiện đại"}
 ]
};

function clone(v){return JSON.parse(JSON.stringify(v))}
function nowLabel(){return new Date().toLocaleString("vi-VN",{hour:"2-digit",minute:"2-digit",day:"2-digit",month:"2-digit"})}
function id(prefix){return prefix+"_"+Date.now().toString(36)+"_"+Math.random().toString(36).slice(2,6)}
function defaultProject(name, projectId){
 var content=clone(templateContent);
 return {
  id:projectId||id("web"),
  name:name||"Water Purifier Website",
  branch:"main",
  owner:"luan20495",
  framework:"Next.js + React",
  visibility:"private",
  authMode:"sso",
  domain:"web42.apps.company.vn",
  customDomain:"",
  deploymentMode:"static",
  deploymentTarget:"self-host",
  repository:"luan20495/system-web-studio",
  published:false,
  publishedAt:"",
  content:content,
  messages:[
   {id:"m1",role:"user",content:"Tạo website bán máy lọc nước hiện đại, có hero, sản phẩm, đánh giá khách hàng và form liên hệ."},
   {id:"m2",role:"ai",content:"Đã tạo bản nháp. Frontend đang chạy hoàn toàn bằng mock/local state.",meta:["Navbar","Hero","ProductGrid","Testimonials","ContactForm"]}
  ],
  versions:[{id:"v1",label:"Initial",createdAt:nowLabel(),summary:"Initial website",commitSha:"028ec7",content:clone(content)}]
 };
}
function defaultState(){
 var p1=defaultProject("Water Purifier Website","web_00042");
 var p2=defaultProject("Sales Campaign Landing","web_00057");
 p2.content.heroTitle="Ưu đãi tháng này cho gia đình Việt.";
 p2.content.heroDescription="Landing page chiến dịch mẫu để thử project switcher.";
 return {activeProjectId:p1.id,projects:[p1,p2],device:"desktop",saved:true};
}
function load(){
 try{
  var parsed=JSON.parse(localStorage.getItem(STORAGE_KEY)||"null");
  if(parsed&&parsed.projects&&parsed.projects.length)return parsed;
 }catch(e){}
 return defaultState();
}
var state=load();
function persist(){localStorage.setItem(STORAGE_KEY,JSON.stringify(state));setSaved(true)}
function project(){return state.projects.find(function(p){return p.id===state.activeProjectId})||state.projects[0]}
function setSaved(v){state.saved=v;var el=document.getElementById("saveState");if(el){el.textContent=v?"✓ Saved":"Unsaved";el.classList.toggle("warn",!v)}}
function showToast(msg){var t=document.getElementById("toast");t.textContent=msg;t.style.display="block";clearTimeout(showToast.timer);showToast.timer=setTimeout(function(){t.style.display="none"},2800)}
function escapeHtml(s){return String(s).replace(/[&<>"']/g,function(c){return {"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]})}
function pushUndo(label){
 var p=project();
 undoStack.push({label:label,content:clone(p.content),messages:clone(p.messages)});
 if(undoStack.length>30)undoStack.shift();
 redoStack=[];
 updateUndoButtons();
}
function addVersion(summary){
 var p=project();
 p.versions.unshift({id:id("v"),label:"v"+(p.versions.length+1),createdAt:nowLabel(),summary:summary,commitSha:Math.random().toString(16).slice(2,9),content:clone(p.content)});
 if(p.versions.length>20)p.versions.length=20;
}
function mutate(summary,fn){
 pushUndo(summary);
 fn(project());
 addVersion(summary);
 setSaved(false);
 persist();
 renderAll();
}
function updateUndoButtons(){
 var u=document.getElementById("undoBtn"),r=document.getElementById("redoBtn");
 if(u)u.disabled=undoStack.length===0;
 if(r)r.disabled=redoStack.length===0;
}
window.undoChange=function(){
 if(!undoStack.length)return;
 var p=project();
 redoStack.push({label:"redo",content:clone(p.content),messages:clone(p.messages)});
 var prev=undoStack.pop();
 p.content=clone(prev.content);p.messages=clone(prev.messages);
 addVersion("Undo: "+prev.label);persist();renderAll();showToast("Đã Undo thay đổi gần nhất.");
};
window.redoChange=function(){
 if(!redoStack.length)return;
 var p=project();
 undoStack.push({label:"undo",content:clone(p.content),messages:clone(p.messages)});
 var next=redoStack.pop();
 p.content=clone(next.content);p.messages=clone(next.messages);
 addVersion("Redo");persist();renderAll();showToast("Đã Redo.");
};

function renderHeader(){
 var p=project();
 document.getElementById("projectName").textContent=p.name;
 document.getElementById("projectMeta").textContent=p.id+" • "+p.branch+" • "+p.owner;
 document.getElementById("envBadge").textContent="Frontend only";
 document.getElementById("publishBtn").textContent=p.published?"Republish":"Publish";
 setSaved(state.saved!==false);
 updateUndoButtons();
}
function renderProjects(){
 var p=project(),html="";
 state.projects.forEach(function(item){
  html+='<button class="projectItem '+(item.id===p.id?"active":"")+'" onclick="switchProject(\''+item.id+'\')"><div><b>'+escapeHtml(item.name)+'</b><br><span>'+escapeHtml(item.id)+'</span></div><span>'+(item.published?"Published":"Draft")+'</span></button>';
 });
 html+='<div class="projectDivider"></div><button class="projectItem" onclick="openNewProject()"><div><b>＋ New project</b><br><span>Create from starter template</span></div></button>';
 document.getElementById("projectMenu").innerHTML=html;
}
function renderChat(){
 var p=project(),chat=document.getElementById("chatMessages"),html="";
 p.messages.forEach(function(m){
  html+='<div class="message '+(m.role==="user"?"user":"ai")+'"><div class="bubble">'+escapeHtml(m.content);
  if(m.meta&&m.meta.length){html+='<div class="chips">'+m.meta.map(function(x){return '<span class="chip">'+escapeHtml(x)+'</span>'}).join("")+'</div>'}
  html+='</div></div>';
 });
 chat.innerHTML=html;chat.scrollTop=chat.scrollHeight;
}
function renderPreview(){
 var p=project(),c=p.content,canvas=document.getElementById("canvas");
 canvas.className="canvas"+(state.device==="desktop"?"":" "+state.device);
 document.getElementById("heroEyebrow").textContent=c.heroEyebrow;
 document.getElementById("heroTitle").textContent=c.heroTitle;
 document.getElementById("heroDescription").textContent=c.heroDescription;
 var productHtml=c.products.map(function(prod){
  return '<article class="productCard" onclick="openProductEditor(\''+prod.id+'\')"><span class="editBadge">Edit</span><div class="productImage"></div><div class="productBody"><b>'+escapeHtml(prod.name)+'</b><span>'+escapeHtml(prod.description)+'</span></div></article>';
 }).join("");
 document.getElementById("products").innerHTML=productHtml;
 var compare=document.getElementById("comparisonSection");
 compare.classList.toggle("hidden",!c.showComparison);
 if(c.showComparison){
  document.getElementById("compareGrid").innerHTML=c.products.slice(0,3).map(function(prod){return '<article class="compareCard"><b>'+escapeHtml(prod.name)+'</b><span>'+escapeHtml(prod.description)+'</span></article>'}).join("");
 }
 document.getElementById("testimonialsSection").classList.toggle("hidden",!c.showTestimonials);
 document.querySelectorAll("[data-device]").forEach(function(b){b.classList.toggle("active",b.getAttribute("data-device")===state.device)});
}
function renderHistory(){
 var p=project(),el=document.getElementById("versionList");
 if(!p.versions.length){el.innerHTML='<div class="empty">Chưa có version.</div>';return}
 el.innerHTML=p.versions.map(function(v,i){
  return '<article class="versionItem"><div class="versionHead"><div><b>'+escapeHtml(v.label)+'</b><div class="meta">'+escapeHtml(v.createdAt)+'</div></div><button class="smallBtn" '+(i===0?"disabled":"")+' onclick="restoreVersion(\''+v.id+'\')">'+(i===0?"Current":"Restore")+'</button></div><p>'+escapeHtml(v.summary)+'</p><code>'+escapeHtml(v.commitSha)+'</code></article>';
 }).join("");
}
function renderSettings(){
 var p=project();
 ["name","framework","domain","customDomain","repository"].forEach(function(k){var el=document.getElementById("set_"+k);if(el)el.value=p[k]||""});
 document.getElementById("set_visibility").value=p.visibility;
 document.getElementById("set_authMode").value=p.authMode;
 document.getElementById("set_deploymentMode").value=p.deploymentMode;
 document.getElementById("set_deploymentTarget").value=p.deploymentTarget;
 document.getElementById("backendStatus").textContent="Not connected";
 document.getElementById("aiStatus").textContent="Mock adapter";
}
function renderAll(){renderHeader();renderProjects();renderChat();renderPreview();renderHistory();renderSettings()}
window.toggleProjectMenu=function(){
 document.getElementById("projectMenu").classList.toggle("show");
};
window.switchProject=function(pid){
 state.activeProjectId=pid;undoStack=[];redoStack=[];persist();document.getElementById("projectMenu").classList.remove("show");renderAll();showToast("Đã chuyển project.");
};
window.openNewProject=function(){document.getElementById("projectMenu").classList.remove("show");openOverlay("newProject")};
window.createProject=function(){
 var name=document.getElementById("newProjectName").value.trim();
 if(!name){showToast("Nhập tên project trước.");return}
 var p=defaultProject(name);
 state.projects.unshift(p);state.activeProjectId=p.id;undoStack=[];redoStack=[];persist();closeOverlay("newProject");document.getElementById("newProjectName").value="";renderAll();showToast("Đã tạo project mới bằng dummy data.");
};
window.openOverlay=function(id){document.getElementById(id).classList.add("show")};
window.closeOverlay=function(id){document.getElementById(id).classList.remove("show")};
window.settingsOpen=function(){renderSettings();openOverlay("settings")};
window.historyOpen=function(){renderHistory();openOverlay("history")};
window.publishOpen=function(){
 var p=project();publishChoice=p.visibility;
 document.querySelectorAll(".publishChoice").forEach(function(x){x.classList.toggle("selected",x.getAttribute("data-value")===publishChoice)});
 document.getElementById("publishSummary").textContent=p.name+" • "+(p.customDomain||p.domain);
 openOverlay("publish");
};
window.selectPublish=function(value){
 publishChoice=value;
 document.querySelectorAll(".publishChoice").forEach(function(x){x.classList.toggle("selected",x.getAttribute("data-value")===value)});
};
window.saveSettings=function(){
 var p=project();
 pushUndo("Project settings");
 p.name=document.getElementById("set_name").value.trim()||p.name;
 p.framework=document.getElementById("set_framework").value.trim()||p.framework;
 p.visibility=document.getElementById("set_visibility").value;
 p.authMode=document.getElementById("set_authMode").value;
 p.domain=document.getElementById("set_domain").value.trim();
 p.customDomain=document.getElementById("set_customDomain").value.trim();
 p.repository=document.getElementById("set_repository").value.trim();
 p.deploymentMode=document.getElementById("set_deploymentMode").value;
 p.deploymentTarget=document.getElementById("set_deploymentTarget").value;
 addVersion("Update project settings");persist();closeOverlay("settings");renderAll();showToast("Đã lưu Settings vào local state.");
};
window.publishNow=async function(){
 var btn=document.getElementById("confirmPublish");btn.disabled=true;btn.textContent="Publishing…";
 await mockApi.publish(project().id,publishChoice);
 var p=project();p.visibility=publishChoice;p.published=true;p.publishedAt=nowLabel();addVersion("Publish "+publishChoice);persist();
 btn.disabled=false;btn.textContent="Publish";closeOverlay("publish");renderAll();showToast("Publish mock hoàn tất. Backend deploy sẽ nối vào adapter sau.");
};
window.restoreVersion=function(versionId){
 var p=project(),v=p.versions.find(function(x){return x.id===versionId});
 if(!v||!v.content)return;
 pushUndo("Restore "+v.label);p.content=clone(v.content);addVersion("Restore "+v.label);persist();closeOverlay("history");renderAll();showToast("Đã restore "+v.label+".");
};
window.setDevice=function(type){
 state.device=type;persist();renderPreview();
};
window.scrollPreview=function(target){
 var el=document.getElementById(target);if(el)el.scrollIntoView({behavior:"smooth",block:"start"});
};
window.openProductEditor=function(productId){
 editingProductId=productId;var prod=project().content.products.find(function(x){return x.id===productId});if(!prod)return;
 document.getElementById("editProductName").value=prod.name;
 document.getElementById("editProductDescription").value=prod.description;
 openOverlay("productEditor");
};
window.saveProduct=function(){
 var p=project(),prod=p.content.products.find(function(x){return x.id===editingProductId});if(!prod)return;
 mutate("Edit product "+prod.name,function(){
  prod.name=document.getElementById("editProductName").value.trim()||prod.name;
  prod.description=document.getElementById("editProductDescription").value.trim()||prod.description;
 });
 closeOverlay("productEditor");showToast("Đã cập nhật product card.");
};
window.deleteProduct=function(){
 var p=project(),prod=p.content.products.find(function(x){return x.id===editingProductId});if(!prod)return;
 var pid=prod.id;mutate("Delete product "+prod.name,function(pr){pr.content.products=pr.content.products.filter(function(x){return x.id!==pid})});
 closeOverlay("productEditor");showToast("Đã xóa product card.");
};
window.submitLead=function(e){
 e.preventDefault();var form=e.target;var name=form.elements.leadName.value.trim(),phone=form.elements.leadPhone.value.trim();
 if(!name||!phone){showToast("Vui lòng nhập họ tên và số điện thoại.");return}
 form.reset();showToast("Đã gửi form ở chế độ mock. Sau này nối POST /leads.");
};
window.resetDemo=function(){
 if(!confirm("Reset toàn bộ dummy/local data của demo?"))return;
 localStorage.removeItem(STORAGE_KEY);state=defaultState();undoStack=[];redoStack=[];persist();closeOverlay("settings");renderAll();showToast("Đã reset demo.");
};

var mockApi={
 prompt:function(text){
  return new Promise(function(resolve){
   setTimeout(function(){
    var t=text.toLowerCase(),action="Đã ghi nhận yêu cầu.";
    if(t.includes("so sánh")||t.includes("compare"))action="comparison";
    else if(t.includes("thêm")&&t.includes("sản phẩm"))action="add-product";
    else if((t.includes("bỏ")||t.includes("xóa"))&&t.includes("đánh giá"))action="remove-testimonials";
    else if(t.includes("hiện")&&t.includes("đánh giá"))action="show-testimonials";
    else if(t.includes("rút gọn")&&t.includes("hero"))action="short-hero";
    resolve({ok:true,action:action});
   },420);
  });
 },
 publish:function(){return new Promise(function(resolve){setTimeout(function(){resolve({ok:true})},520)})}
};

window.sendPrompt=async function(){
 var box=document.getElementById("prompt"),text=box.value.trim();if(!text)return;
 var p=project();pushUndo("Prompt: "+text);
 p.messages.push({id:id("m"),role:"user",content:text});
 box.value="";setSaved(false);renderChat();
 var send=document.getElementById("sendBtn");send.disabled=true;send.textContent="Working…";
 var result=await mockApi.prompt(text),out="Mock AI đã nhận prompt. Chưa có LLM thật nên chỉ các rule demo được áp dụng.";
 if(result.action==="comparison"){p.content.showComparison=true;out="Đã thêm ComparisonBlock từ registry."}
 if(result.action==="add-product"){p.content.products.push({id:id("p"),name:"Ultra Fresh",description:"Thiết kế mới • khoáng tự nhiên • dummy product"});out="Đã thêm ProductCard mới."}
 if(result.action==="remove-testimonials"){p.content.showTestimonials=false;out="Đã ẩn Testimonials."}
 if(result.action==="show-testimonials"){p.content.showTestimonials=true;out="Đã hiện lại Testimonials."}
 if(result.action==="short-hero"){p.content.heroTitle="Nước sạch. Sống khỏe.";out="Đã rút gọn hero."}
 p.messages.push({id:id("m"),role:"ai",content:out,meta:["mock AI","local state","API-ready"]});
 addVersion(text);persist();send.disabled=false;send.textContent="Send ↑";renderAll();
};

document.addEventListener("keydown",function(e){
 if(e.key==="Escape"){
  document.querySelectorAll(".overlay.show").forEach(function(x){x.classList.remove("show")});
  document.getElementById("projectMenu").classList.remove("show");
 }
 var mod=e.metaKey||e.ctrlKey;
 if(mod&&e.key.toLowerCase()==="z"&&!e.shiftKey){e.preventDefault();undoChange()}
 if(mod&&(e.key.toLowerCase()==="y"||(e.shiftKey&&e.key.toLowerCase()==="z"))){e.preventDefault();redoChange()}
 if(mod&&e.key.toLowerCase()==="s"){e.preventDefault();persist();showToast("Saved locally.")}
});
document.addEventListener("click",function(e){
 var menu=document.getElementById("projectMenu"),btn=document.getElementById("projectSwitcher");
 if(menu.classList.contains("show")&&!menu.contains(e.target)&&!btn.contains(e.target))menu.classList.remove("show");
});
document.getElementById("prompt").addEventListener("keydown",function(e){if(e.key==="Enter"&&!e.shiftKey){e.preventDefault();sendPrompt()}});
document.querySelectorAll(".overlay").forEach(function(o){o.addEventListener("mousedown",function(e){if(e.target===o)closeOverlay(o.id)})});
renderAll();
})();
