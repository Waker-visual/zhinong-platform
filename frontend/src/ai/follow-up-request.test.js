import test from 'node:test';
import assert from 'node:assert/strict';
import { createFollowUpLoader } from './follow-up-request.js';

const messages = [{ id:'u', role:'user', content:'现在需要灌溉吗？' }, { id:'m', role:'assistant', content:'先核对土壤水分。', mode:'llm' }];
const context = { farmId:'farm', conversationId:'chat', messageId:'m' };
const prediction = ctx => ({ ...ctx, source:'model', suggestions:['哪些地块需要优先复测？','灌溉前还要确认哪些条件？','灌溉后怎样核对效果？'] });
function deferred() { let resolve, reject; const promise = new Promise((yes,no) => { resolve=yes;reject=no; }); return { promise, resolve, reject }; }

test('topic suggestions appear before the model resolves; result is cached', async () => {
  const pending=deferred(), updates=[]; let calls=0;
  const loader=createFollowUpLoader({ request:()=>{calls++;return pending.promise;}, update:v=>updates.push(v) });
  const work=loader.load(context,messages,true);
  assert.equal(updates[0].source,'context'); assert.equal(updates[0].suggestions.length,3);
  pending.resolve(prediction(context)); await work;
  assert.equal(updates.at(-1).source,'model');
  await loader.load(context,messages,true); assert.equal(calls,1);
  loader.dispose();
});

test('late results cannot replace another conversation even if transport ignores abort', async () => {
  const first=deferred(), second=deferred(), updates=[]; let signal;
  const loader=createFollowUpLoader({ request:(ctx,s)=>{ if(ctx.conversationId==='chat'){signal=s;return first.promise;}return second.promise; }, update:v=>updates.push(v) });
  const old=loader.load(context,messages,true);
  const nextContext={...context,conversationId:'next'};
  const next=loader.load(nextContext,messages,true);
  assert.equal(signal.aborted,true);
  second.resolve(prediction(nextContext)); await next;
  const count=updates.length; first.resolve(prediction(context)); await old;
  assert.equal(updates.length,count); loader.dispose();
});

test('model outage silently keeps usable local guidance without a retry storm', async () => {
  const updates=[];let calls=0;
  const loader=createFollowUpLoader({ request:async()=>{calls++;throw new Error('timeout');}, update:v=>updates.push(v) });
  await loader.load(context,messages,true); await loader.load(context,messages,true);
  assert.equal(updates.at(-1).source,'context');assert.equal(calls,1);loader.dispose();
});

test('rule mode never calls the model endpoint', async () => {
  const loader=createFollowUpLoader({ request:()=>assert.fail('unnecessary request'), update:v=>assert.equal(v.source,'context') });
  await loader.load(context,messages,false);loader.dispose();
});

test('focused choices are not replaced by a late prediction', async () => {
  const pending=deferred(),updates=[];
  const loader=createFollowUpLoader({ request:()=>pending.promise,update:v=>updates.push(v) });
  const work=loader.load(context,messages,true);loader.freeze();
  pending.resolve(prediction(context));await work;
  assert.equal(updates.length,1);assert.equal(updates[0].source,'context');loader.dispose();
});

test('wrong message/farm responses fall back, and dispose prevents all later updates', async () => {
  const pending=deferred(),updates=[];let calls=0;
  const loader=createFollowUpLoader({ request:()=>++calls===1?Promise.resolve(prediction({...context,farmId:'other'})):pending.promise,update:v=>updates.push(v) });
  await loader.load(context,messages,true);assert.equal(updates.at(-1).source,'context');
  const work=loader.load({...context,messageId:'new'},messages,true);
  const count=updates.length;loader.dispose();pending.resolve(prediction({...context,messageId:'new'}));await work;
  assert.equal(updates.length,count);
});
