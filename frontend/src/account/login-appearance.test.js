import test from 'node:test';
import assert from 'node:assert/strict';
import 'vue';

test('scene theme is temporary, and releasing it restores the latest account preference',async()=>{
  const saved=new Map([['zhinong-appearance',JSON.stringify({themeMode:'DARK',accent:'FOREST'})]]);
  globalThis.localStorage={getItem:key=>saved.get(key),setItem:(key,value)=>saved.set(key,value)};
  globalThis.document={documentElement:{dataset:{}}};
  globalThis.window={setTimeout:()=>0};
  const {setLoginSceneMode,clearLoginSceneMode,applyAppearance}=await import('./appearance.js');
  const before=saved.get('zhinong-appearance');
  setLoginSceneMode('LIGHT');
  assert.equal(document.documentElement.dataset.theme,'light');
  assert.equal(saved.get('zhinong-appearance'),before);
  applyAppearance({themeMode:'DARK',accent:'AMBER'});
  assert.equal(document.documentElement.dataset.theme,'light');
  clearLoginSceneMode();
  assert.equal(document.documentElement.dataset.theme,'dark');
  assert.equal(document.documentElement.dataset.accent,'AMBER'.toLowerCase());
  assert.equal(JSON.parse(saved.get('zhinong-appearance')).themeMode,'DARK');
});
