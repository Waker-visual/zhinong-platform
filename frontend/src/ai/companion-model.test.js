import test from 'node:test';
import assert from 'node:assert/strict';
import { companionPresentation, companionTopics, prepareCompanionQuestion } from './companion-model.js';

test('tool and message activity completion do not finish the whole response', () => {
  const run = { status: 'running', text: '', activities: [{ status: 'completed' }] };
  assert.equal(companionPresentation({ sending: true, run }).state, 'thinking');
  assert.equal(companionPresentation({ sending: true, run: { ...run, text: '部分回答' } }).state, 'answering');
  assert.equal(companionPresentation({ sending: true, run: { ...run, status: 'completed' } }).state, 'loading');
});

test('partial text never masks failure or cancellation', () => {
  assert.equal(companionPresentation({ sending: true, run: { status: 'error', text: '部分回答' } }).state, 'error');
  assert.equal(companionPresentation({ sending: true, run: { status: 'cancelled', text: '部分回答' } }).state, 'stopped');
  assert.match(companionPresentation({ run: { status: 'cancelled', adapter: 'sync' } }).message, /后台处理/);
});

test('opening history or refreshing does not celebrate a completed response', () => {
  assert.equal(companionPresentation().state, 'idle');
  assert.equal(companionPresentation({ loading: true, outcome: 'completed' }).state, 'loading');
  assert.equal(companionPresentation({ outcome: 'completed' }).state, 'done');
  assert.equal(companionPresentation({ error: '读取失败', outcome: 'completed' }).state, 'error');
});

test('topic preparation preserves drafts exactly and respects active requests', () => {
  const draft = '  请看看分区一\n的土壤读数  ';
  const prepared = prepareCompanionQuestion(draft, companionTopics[0]);
  assert.equal(prepared.question, draft);
  assert.equal(prepared.changed, false);
  assert.match(prepared.notice, /保留/);
  assert.deepEqual(prepareCompanionQuestion('', companionTopics[0], true), { question: '', changed: false, notice: '' });
});

test('topic preparation only supplies an editable question', () => {
  for (const topic of companionTopics) {
    const prepared = prepareCompanionQuestion('', topic);
    assert.equal(prepared.question, topic.question);
    assert.equal(prepared.changed, true);
    assert.match(prepared.notice, /准备好再发送/);
    assert.ok(prepared.question.length <= 2000);
  }
});
