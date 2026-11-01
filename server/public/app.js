const paths = {
 more:'<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
 account:'<circle cx="12" cy="8" r="4"/><path d="M4 22v-2a8 8 0 0 1 16 0v2"/>',
 back:'<path d="m14 6-6 6 6 6"/>',
 chevron:'<path d="m6 14 6-6 6 6"/>',
 close:'<path d="m6 6 12 12M6 18 18 6"/>',
 stop:'<rect x="6" y="6" width="12" height="12" rx="2"/>',
 plus:'<path d="M12 5v14M5 12h14"/>',
 settings:'<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="3" fill="currentColor"/><circle cx="15" cy="17" r="3" fill="currentColor"/>',
 bell:'<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/>',
 pin:'<path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/>',
 lock:'<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/><path d="M12 15v2"/>',
 shield:'<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6Z"/><path d="m8.5 12 2.5 2.5 4.5-5"/>',
 arrow:'<path d="M4 12h16m-6-6 6 6-6 6"/>',
 dashboard:'<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
 phone:'<rect x="6" y="2" width="12" height="20" rx="2.5"/><path d="M10 18h4M11 5h2"/>',
 logout:'<path d="M9 4H4v16h5m0-8h12m-4-4 4 4-4 4"/>',
 refresh:'<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6.1 6.1A8 8 0 0 1 20 12M4 12a8 8 0 0 0 13.9 5.9"/>',
 info:'<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10v.2"/>',
 target:'<circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2"/><path d="M12 2v3m0 14v3M2 12h3m14 0h3"/>',
 copy:'<rect x="8" y="8" width="12" height="13" rx="2"/><path d="M16 8V4a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v12a1 1 0 0 0 1 1h4"/>',
 battery:'<rect x="2" y="6" width="18" height="12" rx="2"/><path d="M23 10v4M6 10v4m4-4v4m4-4v4"/>',
 clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
 eye:'<path d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
 check:'<path d="m5 12 4 4L19 6"/>'
};
const $ = id => document.getElementById(id);
const icon = name => `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || paths.info}</svg>`;
document.querySelectorAll('[data-icon]').forEach(el => { el.innerHTML = icon(el.dataset.icon); });
let map, markerLayer, circle, latest, poll, toastTimer, selectedDevice, currentDevice, deviceList=[], mfaEnabled=false, editingDevice;
let loadGeneration=0,lastMappedDevice,ringBusy=false;
const forDevice=()=>({device_id:selectedDevice});
const relative = value => {
  const minutes = Math.max(0, Math.floor((Date.now() - Date.parse(value)) / 60000));
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes} min ago`;
  if (minutes < 1440) return `${Math.floor(minutes / 60)} hr ago`;
  return `${Math.floor(minutes / 1440)} days ago`;
};
function toast(message) { $('toast').textContent = message; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 4500); }
async function api(route, data) {
  const response = await fetch(route, { method: data === undefined ? 'GET' : 'POST', headers: data === undefined ? {} : { 'Content-Type': 'application/json' }, body: data === undefined ? undefined : JSON.stringify(data) });
  const result = await response.json();
  if (!response.ok) { if (response.status === 401 && route !== '/api/login') showLogin(); throw Object.assign(new Error(result.error || 'Could not connect. Please try again.'),{status:response.status}); }
  return result;
}
function showLogin() { document.querySelectorAll('[popover]').forEach(popover=>{if(popover.matches(':popover-open'))popover.hidePopover();});clearInterval(poll); for(const dialog of document.querySelectorAll('dialog[open]'))dialog.close(); $('login-code-field').hidden=true; $('login-code').value=''; $('dashboard').hidden = true; $('login').hidden = false; $('password').focus(); }
async function showDashboard() {
  $('login').hidden = true; $('dashboard').hidden = false;

  await load(); clearInterval(poll); poll = setInterval(() => { if (!document.hidden) load(); }, 15000);
}
function validReport(report){return report && Number.isFinite(report.lat) && Number.isFinite(report.lng);}
function centerDevice(){
 if(!map || !validReport(latest))return;
 map.setView([latest.lat,latest.lng],15,{animate:false});
 const panel=$('device-panel').getBoundingClientRect();
 map.panBy(innerWidth<=600?[0,panel.height/2]:[-panel.right/2,0],{animate:false});
}
function showAllDevices(){
 if(!map)return;
 const positions=deviceList.filter(device=>validReport(device.report)).map(device=>[device.report.lat,device.report.lng]);
 if(!positions.length && validReport(latest))positions.push([latest.lat,latest.lng]);
 if(!positions.length)return;
 const panel=$('device-panel').getBoundingClientRect();
 map.fitBounds(L.latLngBounds(positions),{maxZoom:15,paddingTopLeft:innerWidth<=600?[40,40]:[panel.right+32,40],paddingBottomRight:innerWidth<=600?[40,panel.height+32]:[60,40],animate:false});
}
function panelView(view){
 $('device-panel').dataset.view=view;$('device-panel').classList.remove('collapsed');
 $('list-heading').hidden=view!=='list';$('detail-heading').hidden=view!=='detail';
 $('fleet-devices').hidden=view!=='list';$('device-detail').hidden=view!=='detail';
 $('panel-body').hidden=false;$('panel-body').scrollTop=0;
 $('panel-collapse').setAttribute('aria-expanded','true');
 $('panel-collapse').setAttribute('aria-label','Collapse device list');$('panel-collapse').title='Collapse device list';
}
async function chooseDevice(id){
 selectedDevice=id;loadGeneration++;await load();
 if(currentDevice?.id===id && selectedDevice===id){panelView('detail');requestAnimationFrame(centerDevice);$('devices-back').focus({preventScroll:true});}
}
function drawMap(report){
 $('recenter').hidden=!validReport(report);
 const positions=deviceList.filter(device=>validReport(device.report));
 $('map-empty').hidden=positions.length>0 || validReport(report);
 if(!window.L)return;
 const first=!map;
 if(first){
  map=L.map('map',{zoomControl:false,scrollWheelZoom:true}).setView([20,0],2);
  L.control.zoom({position:'topright'}).addTo(map);
  const tiles=L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener noreferrer">OpenStreetMap</a>'}).addTo(map);
  let tileErrorShown=false;tiles.on('tileerror',()=>{if(!tileErrorShown){toast('Map unavailable. Device coordinates are in its information panel.');tileErrorShown=true;}});
  markerLayer=L.layerGroup().addTo(map);
 }
 markerLayer.clearLayers();
 // The demo also exposes a sample report before any devices have been enrolled.
 const points=positions.length?positions:validReport(report)?[{id:selectedDevice,name:'Sample device',report}]:[];
 for(const device of points){
  const selected=device.id===selectedDevice;
  const pin=L.marker([device.report.lat,device.report.lng],{icon:L.divIcon({className:'',html:'<span class="map-pin'+(selected?' selected':'')+'">'+icon('phone')+'</span>',iconSize:[40,40],iconAnchor:[20,20]}),zIndexOffset:selected?1000:0,title:device.name}).addTo(markerLayer);
  pin.getElement()?.setAttribute('aria-label',device.name);
  const label=document.createElement('span');label.textContent=device.name;pin.bindTooltip(label,{direction:'top',offset:[0,-22]});
  if(device.id)pin.on('click',()=>chooseDevice(device.id));
 }
 if(validReport(report))circle=L.circle([report.lat,report.lng],{radius:report.accuracy,color:'#52784b',weight:1,fillColor:'#789c68',fillOpacity:.1,interactive:false}).addTo(markerLayer);
 requestAnimationFrame(()=>{map.invalidateSize();if(first)showAllDevices();});
}
function activityItem(title, detail, date, glyph) {
  const row = document.createElement('div'); row.className = 'activity-row';
  const symbol = document.createElement('span'); symbol.className = 'activity-symbol'; symbol.innerHTML = icon(glyph);
  const copy = document.createElement('div'); copy.className = 'activity-copy';
  const strong = document.createElement('strong'); strong.textContent = title;
  const p = document.createElement('p'); p.textContent = detail; copy.append(strong,p);
  const time = document.createElement('time'); time.dateTime = date; time.textContent = relative(date); time.title = new Date(date).toLocaleString();
  row.append(symbol,copy,time); return row;
}
async function load() {
  try {
    const generation=++loadGeneration;
    const data = await api('/api/dashboard'+(selectedDevice?'?device='+encodeURIComponent(selectedDevice):''));
    if(generation!==loadGeneration)return;
    currentDevice=data.device; selectedDevice=data.device?.id;deviceList=data.devices.filter(device=>!device.revoked_at);
    const fleet=deviceList.map(device=>{
      const item=document.createElement('button');item.type='button';item.className='fleet-device';item.dataset.device=device.id;item.setAttribute('aria-pressed',String(device.id===selectedDevice));
      const name=document.createElement('strong');name.textContent=device.name;
      const seen=document.createElement('span');seen.textContent=device.report?'Location '+relative(device.report.recorded_at).toLowerCase():device.last_seen?'Seen '+relative(device.last_seen).toLowerCase():'Not connected yet';
      const battery=document.createElement('span');battery.className='fleet-battery';battery.textContent=device.report?`${device.report.battery}%`:'';battery.title='Battery in latest location report';
      const symbol=document.createElement('span');symbol.className='fleet-symbol';symbol.innerHTML=icon('phone');
      const heading=document.createElement('span');heading.className='fleet-title';heading.append(name,battery);
      const copy=document.createElement('span');copy.className='fleet-copy';copy.append(heading,seen);item.append(symbol,copy);item.addEventListener('click',()=>chooseDevice(device.id));return item;
    });
    if(!fleet.length){const empty=document.createElement('p');empty.textContent='No devices';fleet.push(empty);}
    const focusedDevice=$('fleet-devices').contains(document.activeElement)?document.activeElement.dataset.device:null;
    $('fleet-devices').replaceChildren(...fleet);
    if(focusedDevice)Array.from($('fleet-devices').children).find(item=>item.dataset.device===focusedDevice)?.focus({preventScroll:true});
    $('device-count').textContent=String(deviceList.length);
    $('device-manage').disabled=!currentDevice || data.demo;$('device-add').disabled=data.demo;
    document.querySelectorAll('.device-name').forEach(el=>el.textContent=currentDevice?.name || (data.demo?'Sample device':'No device selected'));
    const ring=data.ring;
    const ringReady=!!currentDevice?.ring_supported && !data.demo;
    $('ring-start').disabled=ringBusy || !ringReady || ['queued','playing'].includes(ring?.status);
    const soundActive=['queued','playing','unconfirmed'].includes(ring?.status);
    $('ring-stop').disabled=ringBusy || !ringReady || !soundActive;
    $('ring-stop').hidden=!soundActive;$('ring-start').hidden=soundActive;
    const ringLabels={queued:ring?.action==='stop'?'Stop requested. Waiting for the phone.':'Sound queued. Waiting for the phone (up to one minute).',playing:'Phone confirmed playback started.',stopped:'Sound stopped.',expired:'Request expired without confirmation.',unconfirmed:'Sound should have ended; the phone has not confirmed stopping.',failed:'Could not play sound: '+(ring?.error || 'Check the phone’s audio and background settings.')};
    $('ring-status').textContent=data.demo?'Sound controls are disabled in this demo.':!ringReady?'Connect the updated app to enable ringing.':ringLabels[ring?.status] || 'Ready to ring this phone.';
    $('ring-summary').hidden=!ring?.status || ring.status==='stopped';
    $('ring-summary').textContent=({queued:'Sound requested',playing:'Playing sound',stopped:'Sound stopped',expired:'Sound request expired',unconfirmed:'Stop not confirmed',failed:'Sound failed'})[ring?.status]||'';
    $('device-model').textContent=[currentDevice?.manufacturer,currentDevice?.model].filter(Boolean).join(' · ');
    latest = data.report;
    $('connection-error').hidden = true; $('connection-error-detail').textContent='';$('demo-banner').hidden = !data.demo;$('demo-indicator').hidden=!data.demo;
    $('map-subtitle').textContent = latest ? 'Updated '+relative(latest.recorded_at).toLowerCase() : 'No location yet';
    $('location-badge').textContent = latest ? (data.demo ? 'Sample location' : `± ${Math.round(latest.accuracy)} m`) : 'No location yet';
    $('coordinates').textContent = latest ? `${latest.lat.toFixed(5)}°, ${latest.lng.toFixed(5)}°` : 'No coordinates reported';
    $('copy-coordinates').disabled = !latest;
    $('battery').textContent = latest ? `${latest.battery}%${latest.charging ? ' · charging' : ''}` : '—';
    $('last-contact').textContent = currentDevice?.last_seen ? relative(currentDevice.last_seen) : latest ? relative(latest.received_at) : '—';
    $('accuracy').textContent = latest ? `± ${Math.round(latest.accuracy)} m` : '—';
    $('device-state').lastChild.textContent=data.demo?' Preview':['connected','builtin_sent'].includes(data.push?.state)?' Online':data.push?.registered?' Push ready':' Offline';
    const pending = data.commands.some(command => command.status === 'queued');
    $('pending').hidden = !pending; $('retry-push').hidden = !pending || !data.push?.registered; $('wake-sms').hidden = !pending || data.demo; $('locate').disabled = pending || !currentDevice || data.demo;
    $('locate').innerHTML = icon(pending ? 'clock' : 'target') + (pending ? 'Locating…' : 'Locate');
    const pushLabels = {not_registered:'Internet connection not enabled',connected:'Built-in connection online',builtin_sent:'Built-in connection online · wake-up sent',offline:'Built-in connection offline',registered:'Internet push ready',sending:'Sending internet push…',sent:'Push accepted by provider',failed:'Internet push failed',expired:'Push registration expired',rate_limited:'Push rate-limited; retry shortly'};
    $('push-state').textContent = pushLabels[data.push?.state] || 'Internet push not registered';
    $('push-state').title = data.push?.lastError || 'A wake-up does not confirm completion. A fresh phone report completes the request.';
    drawMap(latest);
    if(latest && map && lastMappedDevice!==selectedDevice){if($('device-panel').dataset.view==='detail')centerDevice();lastMappedDevice=selectedDevice;}
    const events = data.reports.map(report => ({ title:data.demo ? 'Sample location check-in' : 'Location received', detail:`${report.lat.toFixed(5)}°, ${report.lng.toFixed(5)}° · ${report.battery}% battery`, date:report.received_at, glyph:'pin' }));
    const labels = {queued:['Location requested','Waiting for the phone to fetch this request.'],completed:['Location request completed','The phone returned a fresh location.'],cancelled:['Request cancelled','This request is no longer active.'],expired:['Request expired','The phone did not respond within 30 minutes.']};
    data.commands.forEach(command => { const [title,detail] = labels[command.status]; events.push({title,detail,date:command.completed_at || command.created_at,glyph:command.status === 'completed' ? 'check' : 'clock'}); });
    events.sort((a,b) => Date.parse(b.date)-Date.parse(a.date));
    $('activity-list').replaceChildren();
    for (const event of events.slice(0,8)) $('activity-list').append(activityItem(event.title,event.detail,event.date,event.glyph));
    if (!events.length) { const empty = document.createElement('div'); empty.className = 'empty-activity'; empty.innerHTML = icon('clock'); const text = document.createElement('span'); text.textContent = 'No activity yet.'; empty.append(text); $('activity-list').append(empty); }
    $('activity-count').textContent = `${Math.min(events.length,8)} event${events.length === 1 ? '' : 's'}`;
    $('sync-status').textContent = `Updated ${new Date().toLocaleTimeString([], {hour:'2-digit',minute:'2-digit'})}`;
  } catch (error) { if(error.status===404 && selectedDevice){selectedDevice=undefined;return load();} $('connection-error-detail').textContent = error.message; $('connection-error').hidden = false; $('sync-status').textContent = 'Could not refresh'; }
}
$('login-form').addEventListener('submit', async event => {
  event.preventDefault(); $('sign-in').disabled = true; $('login-error').textContent = '';
  try { const result=await api('/api/login', {password:$('password').value,code:$('login-code').value});
    if(result.requiresTwoFactor){$('login-code-field').hidden=false;$('login-code').focus();return;}
    $('password').value='';$('login-code').value='';$('login-code-field').hidden=true;await showDashboard(); }
  catch (error) { $('login-error').textContent = error.message; }
  finally { $('sign-in').disabled = false; }
});
$('show-password').addEventListener('click', () => { const show = $('password').type === 'password'; $('password').type = show ? 'text' : 'password'; $('show-password').setAttribute('aria-label',show ? 'Hide password' : 'Show password'); $('show-password').setAttribute('aria-pressed',String(show)); });
async function signOut() { try { await api('/api/logout', {}); showLogin(); } catch(error) { toast(error.message); } }
$('sign-out').addEventListener('click', signOut);
$('refresh').addEventListener('click', async () => { $('refresh').disabled = true; await load(); $('refresh').disabled = false; });
$('locate').addEventListener('click', async () => {
  $('locate').disabled = true;
  try { const result = await api('/api/locate', forDevice()); await load(); toast(result.push?.state === 'builtin_sent' ? 'Wake-up sent over built-in connection. Waiting for phone.' : result.push?.state === 'sent' ? 'Push accepted by provider. Waiting for phone.' : result.push?.error || 'Request queued. Enable an internet connection in the app, or use SMS / periodic checks.'); }
  catch(error) { toast(error.message); $('locate').disabled = false; }
});
$('cancel').addEventListener('click', async () => { try { await api('/api/cancel', forDevice()); await load(); toast('Location request cancelled.'); } catch(error) { toast(error.message); } });
$('recenter').addEventListener('click',centerDevice);
$('copy-coordinates').addEventListener('click', async () => { if (!latest) return; try { await navigator.clipboard.writeText(`${latest.lat.toFixed(5)}, ${latest.lng.toFixed(5)}`); toast('Coordinates copied.'); } catch { toast('Copy unavailable. You can select the coordinates below the map.'); } });
(async () => {
  try { const info = await api('/api/session'); document.querySelectorAll('.device-name').forEach(el => el.textContent = info.deviceName); $('demo-login').hidden = !info.demo; if (info.authenticated) await showDashboard(); else showLogin(); }
  catch { showLogin(); $('login-error').textContent = 'Could not connect to the server. Please reload.'; }
})();

$('wake-sms').addEventListener('click', async () => { try { const result = await api('/api/wake-message', forDevice()); await navigator.clipboard.writeText(result.message); toast('Signed SMS copied. Send it to this phone from another device within 10 minutes.'); } catch (error) { toast(error.message); } });

$('retry-push').addEventListener('click', async () => { $('retry-push').disabled=true; try { const result=await api('/api/retry-push',forDevice()); await load(); toast(result.state==='builtin_sent' ? 'Wake-up sent over built-in connection. Waiting for phone.' : result.state==='sent' ? 'Push accepted by provider. Waiting for phone.' : result.error || 'Push already sent recently. Wait 10 seconds.'); } catch(error) {toast(error.message);} finally {$('retry-push').disabled=false;} });

async function securityState(){const value=await api('/api/security');mfaEnabled=value.twoFactorEnabled;return value;}
function clearSensitive(dialog){for(const field of dialog.querySelectorAll('input,textarea'))field.value='';if(dialog.id==='security-dialog'){$('mfa-qr').removeAttribute('src');$('mfa-confirm-form').hidden=true;$('recovery-panel').hidden=true;}}
for(const name of ['device','security']){$(name+'-dialog').addEventListener('close',()=>clearSensitive($(name+'-dialog')));}
$('device-dialog-close').addEventListener('click',()=>$('device-dialog').close());
$('security-close').addEventListener('click',()=>$('security-dialog').close());
async function openDevice(edit){
 try{
  await securityState();editingDevice=edit?currentDevice?.id:null;
  clearSensitive($('device-dialog'));$('device-error').textContent='';$('device-form').hidden=false;$('device-credentials').hidden=true;
  $('device-dialog-title').textContent=edit?'Manage device':'Add device';$('device-label').value=edit?currentDevice.name:'';
  $('device-save').textContent=edit?'Save name':'Create device';$('device-revoke').hidden=!edit;$('device-revoke-access').hidden=!edit || !currentDevice?.account_access?.enabled;$('device-password').required=!edit;
  $('device-code-field').hidden=!mfaEnabled;$('device-dialog').showModal();$('device-label').focus();
 }catch(error){toast(error.message);}
}
$('device-add').addEventListener('click',()=>openDevice(false));$('device-manage').addEventListener('click',()=>openDevice(true));
const deviceCredentials=()=>({password:$('device-password').value,code:$('device-code').value});
$('device-form').addEventListener('submit',async event=>{
 event.preventDefault();$('device-save').disabled=true;$('device-error').textContent='';
 try{
  if(editingDevice){await api('/api/devices/rename',{device_id:editingDevice,name:$('device-label').value});$('device-dialog').close();await load();toast('Device name saved.');}
  else{
   const result=await api('/api/devices/add',{name:$('device-label').value,...deviceCredentials()});
   $('device-password').value='';$('device-code').value='';$('device-form').hidden=true;$('device-credentials').hidden=false;
   $('enroll-url').value=result.url;$('enroll-token').value=result.token;selectedDevice=result.id;await load();
  }
 }catch(error){$('device-error').textContent=error.message;}finally{$('device-save').disabled=false;}
});
$('device-revoke').addEventListener('click',async()=>{
 $('device-error').textContent='';if(!$('device-password').value){$('device-error').textContent='Enter your dashboard password to revoke this device.';$('device-password').focus();return;}
 if(!confirm('Revoke this device? Its token and internet connection will stop working. History stays on this server.'))return;
 $('device-revoke').disabled=true;
 try{await api('/api/devices/revoke',{device_id:editingDevice,...deviceCredentials()});$('device-dialog').close();selectedDevice=undefined;await load();toast('Device credentials revoked.');}
 catch(error){$('device-error').textContent=error.message;}finally{$('device-revoke').disabled=false;}
});
$('enroll-copy').addEventListener('click',async()=>{try{await navigator.clipboard.writeText($('enroll-token').value);toast('Device token copied.');}catch{toast('Select and copy the token manually.');}});
async function updateSecurity(){const value=await securityState();$('security-status').textContent=value.twoFactorEnabled?`Two-factor authentication enabled · ${value.recoveryCodesRemaining} recovery codes remaining · ${value.sessionCount} active browser sessions`:`Two-factor authentication is off · ${value.sessionCount} active browser sessions`;$('security-code-field').hidden=!mfaEnabled;$('mfa-start').hidden=mfaEnabled;$('mfa-disable').hidden=!mfaEnabled;}
$('security-open').addEventListener('click',async()=>{try{clearSensitive($('security-dialog'));$('security-error').textContent='';await updateSecurity();$('security-dialog').showModal();}catch(error){toast(error.message);}});
const securityCredentials=()=>({password:$('security-password').value,code:$('security-code').value});
async function securityAction(action){$('security-error').textContent='';try{await action();}catch(error){$('security-error').textContent=error.message;}}
$('mfa-start').addEventListener('click',()=>securityAction(async()=>{
 $('mfa-start').disabled=true;
 try{const result=await api('/api/security/setup',securityCredentials());$('security-password').value='';$('mfa-secret').value=result.secret;$('mfa-qr').src=result.qr;$('mfa-confirm-form').hidden=false;$('recovery-panel').hidden=true;$('mfa-confirm-code').focus();}
 finally{$('mfa-start').disabled=false;}
}));
$('mfa-confirm-form').addEventListener('submit',event=>{event.preventDefault();securityAction(async()=>{
 const result=await api('/api/security/confirm',{code:$('mfa-confirm-code').value});
 $('mfa-confirm-form').hidden=true;$('mfa-secret').value='';$('mfa-confirm-code').value='';$('mfa-qr').removeAttribute('src');
 $('recovery-codes').value=result.recoveryCodes.join('\n');$('recovery-panel').hidden=false;await updateSecurity();toast('Two-factor authentication enabled. Save your recovery codes.');
});});
$('mfa-disable').addEventListener('click',()=>securityAction(async()=>{
 if(!confirm('Disable two-factor authentication? Your password will be the only dashboard sign-in factor.'))return;
 await api('/api/security/disable',securityCredentials());clearSensitive($('security-dialog'));await updateSecurity();toast('Two-factor authentication disabled. Other browsers signed out.');
}));
$('sessions-revoke').addEventListener('click',()=>securityAction(async()=>{await api('/api/security/revoke-sessions',securityCredentials());$('security-password').value='';$('security-code').value='';await updateSecurity();toast('Other browser sessions signed out.');}));
$('recovery-copy').addEventListener('click',async()=>{try{await navigator.clipboard.writeText($('recovery-codes').value);toast('Recovery codes copied. Store them separately from your phone.');}catch{toast('Select and copy the codes manually.');}});

async function sendRing(action){
 const device=selectedDevice;if(!device || ringBusy)return;
 ringBusy=true;$('ring-start').disabled=true;$('ring-stop').disabled=true;
 try{
  const result=await api('/api/ring',{device_id:device,action,duration:Number($('ring-duration').value),volume:Number($('ring-volume').value),vibrate:$('ring-vibrate').checked});
  toast(action==='stop'?'Stop requested. Waiting for confirmation.':result.push?.error || 'Sound requested. Waiting for the phone.');
 }catch(error){toast(error.message);}
 finally{ringBusy=false;await load();}
}
$('ring-form').addEventListener('submit',event=>{event.preventDefault();sendRing('start');});
$('ring-stop').addEventListener('click',()=>sendRing('stop'));

$('device-revoke-access').addEventListener('click',async()=>{
 $('device-error').textContent='';
 if(!$('device-password').value){$('device-error').textContent='Enter your dashboard password to revoke account access.';$('device-password').focus();return;}
 if(!confirm('Block this phone from viewing locations and ringing your devices? You can still locate and ring this phone.'))return;
 $('device-revoke-access').disabled=true;
 try{await api('/api/devices/revoke-access',{device_id:editingDevice,...deviceCredentials()});$('device-dialog').close();await load();toast('Account access revoked. This phone remains trackable.');}
 catch(error){$('device-error').textContent=error.message;}finally{$('device-revoke-access').disabled=false;}
});

$('devices-back').addEventListener('click',()=>{panelView('list');showAllDevices();$('panel-collapse').focus({preventScroll:true});});
$('panel-collapse').addEventListener('click',()=>{
 const collapsed=$('device-panel').classList.toggle('collapsed');$('panel-body').hidden=collapsed;
 $('panel-collapse').setAttribute('aria-expanded',String(!collapsed));
 $('panel-collapse').setAttribute('aria-label',collapsed?'Expand device list':'Collapse device list');$('panel-collapse').title=collapsed?'Expand device list':'Collapse device list';
});
for(const [button,dialog] of [['sound-options-open','sound-options-dialog'],['activity-open','activity-dialog']])$(button).addEventListener('click',()=>$(dialog).showModal());
document.querySelectorAll('[data-close]').forEach(button=>button.addEventListener('click',()=>$(button.dataset.close).close()));
for(const button of ['security-open','sign-out'])$(button).addEventListener('click',()=>{if($('account-menu').matches(':popover-open'))$('account-menu').hidePopover();});
window.addEventListener('resize',()=>{if(map)map.invalidateSize();});
