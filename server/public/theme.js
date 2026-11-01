/* Applied before styles load, so a saved dark theme does not flash light. */
(() => {
 const key='waypoint-theme';
 const system=window.matchMedia('(prefers-color-scheme: dark)');
 let preference='system';
 let glass=false;
 try{glass=localStorage.getItem('waypoint-glass')==='true';}catch{}
 try{const saved=localStorage.getItem(key);if(['system','light','dark'].includes(saved))preference=saved;}catch{}
 function apply(){
  const dark=preference==='dark'||(preference==='system'&&system.matches);
  document.documentElement.dataset.glass=String(glass);
  const glassChoice=document.querySelector('#glass-panels');if(glassChoice)glassChoice.checked=glass;
  document.documentElement.dataset.theme=dark?'dark':'light';
  document.documentElement.style.colorScheme=dark?'dark':'light';
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content',dark?'#111916':'#f4f6f3');
  document.querySelectorAll('[data-theme-choice]').forEach(select=>{select.value=preference;});
 }
 function set(value){if(!['system','light','dark'].includes(value))return;preference=value;try{localStorage.setItem(key,value);}catch{}apply();}
 apply();
 system.addEventListener('change',apply);
 window.addEventListener('storage',event=>{if(event.key===key){preference=['light','dark'].includes(event.newValue)?event.newValue:'system';apply();}});
 document.addEventListener('DOMContentLoaded',()=>{document.querySelector('#glass-panels')?.addEventListener('change',event=>{glass=event.target.checked;try{localStorage.setItem('waypoint-glass',String(glass));}catch{}apply();});apply();document.querySelectorAll('[data-theme-choice]').forEach(select=>select.addEventListener('change',()=>set(select.value)));});
})();
