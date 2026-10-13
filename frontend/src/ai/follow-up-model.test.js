import test from 'node:test';
import assert from 'node:assert/strict';
import { contextualFollowUps, followUpContext, normalizeFollowUps, prepareFollowUp } from './follow-up-model.js';

const turn = (question, answer = '应结合农场记录和现场复核，确认依据是否完整。', id = 'answer-1') => [
  { id: `user-${id}`, role: 'user', content: question },
  { id, role: 'assistant', content: answer, mode: 'llm' },
];
const scope = { conversationId: 'conversation-a', farmId: 'farm-a', messageId: 'answer-1' };
const modelResponse = (suggestions = ['还需要补充哪些现场记录？', '请把下一步检查整理成清单。']) => ({ ...scope, source: 'model', suggestions });

test('context belongs to the latest completed assistant turn and carries only three recent user questions', () => {
  const messages = ['前一周经营', '前天天气', '昨天土壤', '今天灌溉'].flatMap((question, index) => turn(question, `回答 ${index}`, `answer-${index}`));
  assert.deepEqual(followUpContext(messages), {
    messageId: 'answer-3', question: '今天灌溉', answer: '回答 3', mode: 'llm', recentQuestions: ['今天灌溉', '昨天土壤', '前天天气'],
  });
});

test('unfinished, failed, cancelled, blank or absent answers do not produce guidance', () => {
  for (const extra of [{ pending: true }, { temporary: true }, { status: 'error' }, { status: 'cancelled' }, { status: 'running' }, { content: '  ' }]) {
    const messages = turn('今天是否需要灌溉？');
    Object.assign(messages[1], extra);
    assert.equal(followUpContext(messages), null);
    assert.deepEqual(contextualFollowUps(messages), []);
  }
  for (const messages of [null, [], [{ role: 'assistant', content: '没有用户问题' }], turn('天气怎样？').slice(0, 1)]) {
    assert.equal(followUpContext(messages), null);
  }
});

test('a newly submitted user question hides guidance from the previous assistant answer', () => {
  const messages = [...turn('现在需要灌溉吗？'), { role: 'user', content: '设备为什么掉线？' }];
  assert.equal(followUpContext(messages), null);
  assert.deepEqual(contextualFollowUps(messages), []);
});

test('the latest irrigation question outranks unrelated themes in a broad rule fallback', () => {
  const messages = turn('当前地块需要灌溉吗？', '设备离线要检查供电。天气影响病虫害。土壤应复测。经营应核查成本。');
  messages[1].mode = 'rule';
  const suggestions = contextualFollowUps(messages);
  assert.equal(suggestions.length, 3);
  assert.ok(suggestions.every((question) => question.includes('灌溉')));
});

test('irrigation evidence questions advance to priorities, effects and field checks instead of asking for data again', () => {
  for (const question of [
    '判断今天是否需要灌溉之前，应当先核对哪些田间数据？请简短回答，不创建或执行任何设备操作。',
    '评估是否浇水需要哪些资料？',
    '请说明灌溉判断的依据。',
  ]) {
    const history = turn(question);
    for (const messages of [history, [...history, ...turn('继续说。', '进一步分析。', 'answer-2')]]) {
      const suggestions = contextualFollowUps(messages);
      assert.equal(suggestions.length, 3);
      assert.match(suggestions[0], /灌溉优先级/);
      assert.match(suggestions[1], /复核效果/);
      assert.match(suggestions[2], /现场检查/);
      assert.ok(suggestions.every((suggestion) => !suggestion.includes('核实哪些数据')));
    }
  }
});

test('short continuation inherits a recent topic without reading broad assistant keywords', () => {
  const messages = [...turn('这周降雨会影响哪些地块？'), ...turn('请再具体一点。', '还应检查设备掉线、病虫害、灌溉和经营情况。', 'answer-2')];
  assert.ok(contextualFollowUps(messages).every((question) => question.includes('天气')));
  assert.ok(contextualFollowUps([...messages, ...turn('还有吗？', '继续分析。', 'answer-3')]).every((question) => question.includes('天气')));
});

test('continuations look back at most three user turns', () => {
  const messages = [...turn('今天需要灌溉吗？'), ...turn('继续', '好的', 'answer-2'), ...turn('再具体一点', '好的', 'answer-3'), ...turn('还有吗', '好的', 'answer-4')];
  assert.ok(contextualFollowUps(messages).every((question) => !question.includes('灌溉')));
});

test('an explicit new topic replaces the earlier topic even when the answer mentions the earlier one', () => {
  const messages = [...turn('今天需要灌溉吗？'), ...turn('设备掉线怎么排查？', '应暂停依赖缺测读数的灌溉判断。', 'answer-2')];
  assert.match(contextualFollowUps(messages)[0], /设备异常/);
});

test('different farm topics receive distinct practical next questions', () => {
  const examples = [
    ['这周天气怎么样？', /天气/],
    ['叶片上出现病斑，要怎么排查？', /症状|病虫/],
    ['今天有哪些农事需要优先处理？', /农事|经营|投入/],
    ['土壤肥力怎么判断？', /土壤/],
    ['传感器掉线后怎么办？', /设备/],
  ];
  const results = examples.map(([question, expected]) => {
    const suggestions = contextualFollowUps(turn(question));
    assert.equal(suggestions.length, 3);
    assert.match(suggestions[0], expected);
    return suggestions[0];
  });
  assert.equal(new Set(results).size, examples.length);
});

test('a concrete unfamiliar question does not inherit a previous farm topic', () => {
  const messages = [...turn('土壤水分怎样？'), ...turn('如何导出这次对话？', '导出前先复核土壤水分。', 'answer-2')];
  assert.ok(contextualFollowUps(messages).every((question) => !question.includes('土壤')));
});

test('long malformed continuations remain bounded and do not inherit an unrelated topic', () => {
  const messages = [...turn('土壤水分怎样？'), ...turn(`${'继续'.repeat(999)}X`, '请确认具体问题。', 'answer-2')];
  assert.ok(contextualFollowUps(messages).every((question) => !question.includes('土壤')));
});

test('previously asked questions are excluded despite punctuation and polite-prefix differences', () => {
  const asked = contextualFollowUps(turn('要不要灌溉？'))[0];
  const messages = [...turn(`请${asked.replace('？', '?')}`), ...turn('继续说灌溉。', '继续复核。', 'answer-2')];
  const suggestions = contextualFollowUps(messages);
  assert.equal(suggestions.length, 3);
  assert.ok(!suggestions.includes(asked));
});

test('local suggestions make no factual numeric claims and do not command equipment', () => {
  for (const topic of ['灌溉', '天气', '病害', '设备', '土壤', '经营', '其他']) {
    for (const question of contextualFollowUps(turn(topic))) {
      assert.ok(question.length <= 100);
      assert.doesNotMatch(question, /\d|立刻开启|立即开启|启动水泵|关闭阀门/);
    }
  }
});

test('suggestion selection preserves an existing draft exactly', () => {
  const draft = '  先核对地块一\n再看地块二  ';
  const result = prepareFollowUp(draft, '请把下一步检查整理成清单。');
  assert.equal(result.question, draft);
  assert.equal(result.changed, false);
  assert.match(result.notice, /保留/);
});

test('suggestion selection only prepares an editable question and respects the busy lock', () => {
  const question = '请把下一步检查整理成清单。';
  assert.deepEqual(prepareFollowUp(' ', ` ${question} `), { question, changed: true, notice: '追问已放入输入框，可以编辑后再发送。' });
  assert.deepEqual(prepareFollowUp('', question, true), { question: '', changed: false, notice: '' });
  assert.deepEqual(prepareFollowUp('', null), { question: '', changed: false, notice: '' });
  assert.equal(prepareFollowUp('', '字'.repeat(2001)).changed, false);
  assert.equal(prepareFollowUp('', '字'.repeat(2000)).question.length, 2000);
});

test('model guidance is trimmed and deduplicated only for the exact active context', () => {
  const response = modelResponse(['  还需要补充哪些现场记录？ ', '请把下一步检查整理成清单。', '还需要补充哪些现场记录?']);
  assert.deepEqual(normalizeFollowUps(response, scope), ['还需要补充哪些现场记录？', '请把下一步检查整理成清单。']);
  for (const key of ['conversationId', 'farmId', 'messageId']) {
    assert.deepEqual(normalizeFollowUps(response, { ...scope, [key]: 'other' }), []);
    assert.deepEqual(normalizeFollowUps(response, { ...scope, [key]: '' }), []);
  }
});

test('missing, fallback or malformed model response leaves contextual guidance available', () => {
  for (const response of [null, {}, [], { ...modelResponse(), source: 'context' }, { ...modelResponse(), suggestions: '不是数组' }, modelResponse(['只有一个有效问题？']), modelResponse(['甲问题？', '乙问题？', '丙问题？', '丁问题？'])]) {
    assert.deepEqual(normalizeFollowUps(response, scope), []);
  }
  assert.deepEqual(normalizeFollowUps(modelResponse(), null), []);
});

test('unsafe, oversized or nonspecific model strings are rejected as a batch', () => {
  for (const invalid of ['<img src=x onerror=alert(1)>', '&lt;script&gt;', '问题一\n问题二', '问题一\u2028问题二', '请查看 https://example.com', '打开 www.example.com', '请登录 example.com.cn', 'javascript:alert(1)', 'data:text/html,hello', '字'.repeat(101), '好？', null, {}, '？！？？']) {
    assert.deepEqual(normalizeFollowUps(modelResponse([invalid, '请把下一步检查整理成清单。']), scope), []);
  }
});

test('duplicate model questions cannot fill the required minimum', () => {
  assert.deepEqual(normalizeFollowUps(modelResponse(['还需要补充哪些现场记录？', '还需要补充哪些现场记录?']), scope), []);
});
