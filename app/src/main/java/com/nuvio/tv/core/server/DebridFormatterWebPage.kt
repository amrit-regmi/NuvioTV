package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.R

object DebridFormatterWebPage {
    fun html(context: Context?): String {
        val appName = context?.getString(R.string.app_name) ?: "NuvioTV"
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<title>$appName - Direct Debrid Formatter</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;600;700;800;900&display=swap" rel="stylesheet">
<style>
  * {
    margin: 0;
    padding: 0;
    box-sizing: border-box;
    -webkit-tap-highlight-color: transparent;
  }
  *:focus, *:active { outline: none !important; }
  body {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: #000;
    color: #fff;
    min-height: 100vh;
    line-height: 1.5;
  }
  .page {
    max-width: 600px;
    margin: 0 auto;
    padding: 0 1.5rem 6rem;
  }
  .header {
    text-align: center;
    padding: 3rem 0 2.5rem;
    border-bottom: 1px solid rgba(255, 255, 255, 0.05);
    margin-bottom: 2.5rem;
  }
  .header-logo {
    height: 40px;
    width: auto;
    margin-bottom: 0.5rem;
    filter: brightness(0) invert(1);
    opacity: 0.9;
  }
  .header p {
    font-size: 0.875rem;
    font-weight: 300;
    color: rgba(255, 255, 255, 0.4);
    letter-spacing: 0.02em;
  }
  .intro {
    margin-bottom: 2.5rem;
  }
  .intro-title {
    font-size: 1rem;
    font-weight: 700;
    letter-spacing: -0.01em;
    margin-bottom: 0.35rem;
  }
  .intro-copy {
    color: rgba(255, 255, 255, 0.42);
    font-size: 0.875rem;
    font-weight: 300;
  }
  .section-label {
    font-size: 0.75rem;
    font-weight: 500;
    color: rgba(255, 255, 255, 0.3);
    letter-spacing: 0.1em;
    text-transform: uppercase;
    margin-bottom: 1rem;
  }
  .chips {
    display: flex;
    gap: 0.5rem;
    flex-wrap: wrap;
    margin-bottom: 2.5rem;
  }
  .chip {
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 100px;
    padding: 0.45rem 0.7rem;
    color: rgba(255, 255, 255, 0.55);
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.72rem;
  }
  .field {
    margin-bottom: 1.5rem;
  }
  .field label {
    display: block;
    font-size: 0.75rem;
    font-weight: 500;
    color: rgba(255, 255, 255, 0.3);
    letter-spacing: 0.1em;
    text-transform: uppercase;
    margin-bottom: 0.75rem;
  }
  textarea {
    width: 100%;
    min-height: 150px;
    resize: vertical;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.12);
    border-radius: 16px;
    padding: 0.9rem 1rem;
    color: #fff;
    font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    font-size: 0.82rem;
    line-height: 1.5;
    transition: border-color 0.3s ease;
  }
  textarea:focus {
    border-color: rgba(255, 255, 255, 0.4);
  }
  #descriptionTemplate {
    min-height: 280px;
  }
  .actions {
    display: flex;
    gap: 0.75rem;
    margin-top: 2rem;
  }
  .btn {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.5rem;
    flex: 1;
    background: transparent;
    border: 1px solid rgba(255, 255, 255, 0.2);
    border-radius: 100px;
    padding: 0.875rem 1.5rem;
    color: #fff;
    font-family: inherit;
    font-size: 0.875rem;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.3s ease;
    white-space: nowrap;
    -webkit-tap-highlight-color: transparent;
  }
  .btn:hover {
    background: #fff;
    color: #000;
    border-color: #fff;
  }
  .btn:active { transform: scale(0.97); }
  .status {
    color: rgba(135, 239, 172, 0.95);
    font-size: 0.875rem;
    font-weight: 300;
    min-height: 24px;
    margin-top: 1rem;
    text-align: center;
  }
  .status.error {
    color: rgba(207, 102, 121, 0.9);
  }
  @media (max-width: 480px) {
    .page { padding: 0 1rem 5rem; }
    .header { padding: 2rem 0 2rem; }
    .header-logo { height: 32px; }
    .actions { flex-direction: column; }
  }
</style>
</head>
<body>
<div class="page">
  <div class="header">
    <img src="/logo.png" alt="NuvioTV" class="header-logo">
    <p>Direct Debrid Formatter</p>
  </div>

  <div class="intro">
    <div class="intro-title">Customize stream labels</div>
    <div class="intro-copy">Adjust the name and description used for Direct Debrid streams.</div>
  </div>

  <div class="section-label">Template fields</div>
  <div class="chips">
    <span class="chip">{stream.resolution}</span>
    <span class="chip">{stream.quality}</span>
    <span class="chip">{stream.visualTags::join(' | ')}</span>
    <span class="chip">{stream.audioTags::join(' | ')}</span>
    <span class="chip">{stream.size::bytes}</span>
    <span class="chip">{service.cached::istrue["Ready"||"Not Ready"]}</span>
  </div>

  <div class="field">
    <label for="nameTemplate">Name Template</label>
    <textarea id="nameTemplate" spellcheck="false"></textarea>
  </div>

  <div class="field">
    <label for="descriptionTemplate">Description Template</label>
    <textarea id="descriptionTemplate" spellcheck="false"></textarea>
  </div>

  <div class="actions">
    <button class="btn" id="defaults">Restore Default</button>
    <button class="btn" id="save">Save Formatter</button>
  </div>
  <div class="status" id="status"></div>
</div>
<script>
let defaults = null;
const nameBox = document.getElementById('nameTemplate');
const descBox = document.getElementById('descriptionTemplate');
const statusBox = document.getElementById('status');
async function load(){
  const res = await fetch('/api/settings');
  const body = await res.json();
  defaults = body.defaults;
  nameBox.value = body.settings.nameTemplate || defaults.nameTemplate;
  descBox.value = body.settings.descriptionTemplate || defaults.descriptionTemplate;
}
async function save(){
  statusBox.textContent = 'Saving...';
  statusBox.className = 'status';
  const res = await fetch('/api/settings',{
    method:'POST',
    headers:{'Content-Type':'application/json; charset=utf-8'},
    body:JSON.stringify({nameTemplate:nameBox.value,descriptionTemplate:descBox.value})
  });
  if(res.ok){
    statusBox.textContent = 'Saved. New streams will use this formatter.';
  }else{
    const body = await res.json().catch(()=>({error:'Could not save'}));
    statusBox.textContent = body.error || 'Could not save';
    statusBox.className = 'status error';
  }
}
document.getElementById('save').addEventListener('click',save);
document.getElementById('defaults').addEventListener('click',()=>{
  if(!defaults)return;
  nameBox.value = defaults.nameTemplate;
  descBox.value = defaults.descriptionTemplate;
});
load().catch(()=>{statusBox.textContent='Could not load formatter settings';statusBox.className='status error';});
</script>
</body>
</html>
""".trimIndent()
    }
}
