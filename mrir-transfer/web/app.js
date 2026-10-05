const $=id=>document.getElementById(id);
let sourceId=null,destinationId=null,busy=false,previewReady=false;
const labels={A:'Job No.',C:'PO No.',D:'MRIR No.',E:'Item number',F:'Material specification',J:'MRF item',K:'Unit',L:'Quantity',M:'Heat No.',O:'Certificate No.',P:'Remarks',Q:'Shipment No.'};
function status(text,error=false){$('status').textContent=text;$('status').classList.toggle('error',error);}
function selected(){return [...document.querySelectorAll('.sheet input:checked')].map(e=>e.value);}
function controls(){
  $('preview').disabled=busy||!sourceId||!destinationId||!selected().length;
  $('download').disabled=busy||!previewReady;
  for(const e of document.querySelectorAll('input,select,#select-all,#select-none'))e.disabled=busy||e.dataset.incompatible==='true';
  $('selected-count').textContent=`${selected().length} selected`;
}
function invalidate(){previewReady=false;$('preview-table').hidden=true;$('preview-rows').replaceChildren();$('summary').textContent='';$('warnings').replaceChildren();controls();}
async function response(response){if(!response.ok){const body=await response.json();throw new Error(body.error||'Request failed.');}return response;}
async function task(action){if(busy)return;busy=true;controls();try{await action();}catch(error){status(error.message,true);}finally{busy=false;controls();}}
async function release(id){if(id)try{await fetch('/api/release?id='+encodeURIComponent(id),{method:'POST'});}catch{}}
for(const role of ['source','destination'])$(role).addEventListener('change',()=>task(async()=>{
  const file=$(role).files[0];invalidate();
  if(role==='source'){await release(sourceId);sourceId=null;$('sheets').replaceChildren();}else{await release(destinationId);destinationId=null;}
  if(!file)return;
  if(file.size>20*1024*1024)throw new Error('Choose a workbook smaller than 20 MB.');
  status('Reading '+file.name+'…');
  const query=new URLSearchParams({role,name:file.name});
  const result=await (await response(await fetch('/api/upload?'+query,{method:'POST',headers:{'Content-Type':'application/octet-stream'},body:file}))).json();
  $(role+'-info').textContent=file.name+' loaded.';
  if(role==='source'){
    sourceId=result.id;
    for(const sheet of result.sheets){
      const label=document.createElement('label');label.className='sheet';
      const input=document.createElement('input');input.type='checkbox';input.value=sheet.name;input.dataset.incompatible=String(!sheet.compatible);input.disabled=!sheet.compatible;
      const name=document.createElement('span');name.textContent=sheet.name;
      const count=document.createElement('small');count.textContent=sheet.compatible?`${sheet.count} item(s)`:'Unsupported layout';
      label.append(input,name,count);$('sheets').append(label);input.addEventListener('change',invalidate);
    }
  }else destinationId=result.id;
  status('Workbook ready. Select your MRIR sheets, then preview.');
}));
$('search').addEventListener('input',()=>{for(const row of document.querySelectorAll('.sheet'))row.hidden=!row.textContent.toLowerCase().includes($('search').value.toLowerCase());});
$('select-all').addEventListener('click',()=>{for(const input of document.querySelectorAll('.sheet input'))if(input.dataset.incompatible!=='true')input.checked=true;invalidate();});
$('select-none').addEventListener('click',()=>{for(const input of document.querySelectorAll('.sheet input'))input.checked=false;invalidate();});
$('skip').addEventListener('change',invalidate);
function parameters(){
  const query=new URLSearchParams({source:sourceId,destination:destinationId,skip:String($('skip').checked)});
  for(const name of selected())query.append('sheet',name);
  for(const select of document.querySelectorAll('#mapping select'))query.set('map_'+select.dataset.column,select.value);
  return query;
}
$('preview').addEventListener('click',()=>task(async()=>{
  status('Checking records and available destination rows…');
  const plan=await (await response(await fetch('/api/preview?'+parameters(),{method:'POST'}))).json();
  $('preview-rows').replaceChildren();$('warnings').replaceChildren();
  for(const warning of plan.warnings){const li=document.createElement('li');li.textContent=warning;$('warnings').append(li);}
  for(const row of plan.rows){
    const tr=document.createElement('tr');
    for(const value of [`${row.sheet} / ${row.sourceRow}`,row.destinationRow,...Object.keys(labels).map(col=>row.values[col]??'')]){const td=document.createElement('td');td.textContent=String(value);tr.append(td);}
    $('preview-rows').append(tr);
  }
  $('preview-table').hidden=!plan.rows.length;
  $('summary').textContent=`${plan.count} record(s) to transfer; ${plan.duplicates} exact duplicate(s) skipped.`+(plan.count>100?' Showing the first 100 records.':'');
  previewReady=plan.count>0;
  status(plan.count?'Preview ready. Review the mapping and values, then download.':'No new records to transfer.');
}));
$('download').addEventListener('click',()=>task(async()=>{
  status('Creating your updated workbook…');
  const result=await response(await fetch('/api/transfer?'+parameters(),{method:'POST'}));
  const blob=await result.blob(),url=URL.createObjectURL(blob),link=document.createElement('a');
  link.href=url;link.download=result.headers.get('Content-Disposition')?.match(/filename="([^"]+)"/)?.[1]||'Free-Issue-transferred.xlsm';
  document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),10000);
  status('Download started. Open the downloaded workbook to review the transferred rows.');
}));
async function setupMapping(){
  try{
    const data=await(await response(await fetch('/api/mapping'))).json();
    const options=[['header:E4','Header E4 — Job No.'],['header:O3','Header O3 — PO No.'],['header:O1','Header O1 — MRIR No.'],['shipment','Shipment number from sheet name'],['blank','Leave blank']];
    const descriptions={A:'Item number',B:'Material specification',F:'MRF item',G:'Unit',H:'Quantity',I:'Heat No.',K:'Certificate No.',P:'Remarks'};
    for(let code=65;code<=83;code++){const col=String.fromCharCode(code);options.push(['row:'+col,`Item column ${col}${descriptions[col]?' — '+descriptions[col]:''}`]);}
    for(const [col,selector] of Object.entries(data.mapping)){
      const tr=document.createElement('tr'),field=document.createElement('td'),column=document.createElement('td'),cell=document.createElement('td'),select=document.createElement('select');
      field.textContent=labels[col];column.textContent=col;select.dataset.column=col;select.setAttribute('aria-label',labels[col]+' source');
      for(const [value,label] of options){if(value==='blank'&&['D','E','F','K','L'].includes(col))continue;const option=document.createElement('option');option.value=value;option.textContent=label;select.append(option);}
      select.value=selector;select.addEventListener('change',invalidate);cell.append(select);tr.append(field,column,cell);$('mapping').append(tr);
    }
  }catch(error){status(error.message,true);}
}
setupMapping();controls();
