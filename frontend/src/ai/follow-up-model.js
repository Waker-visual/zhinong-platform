// Follow-up guidance reads completed conversation turns only. It never sends a
// message or controls equipment; choosing a question still requires user review.
const TOPICS = [
  ['device', /掉线|离线|断连|故障|校准|失联|过期读数|缺测|(?:设备|传感器|监测|读数).{0,8}(?:异常|不准|漂移|恢复)/i],
  ['irrigation', /灌溉|浇水|浇灌|补水|水肥|滴灌|喷灌|irrigat|watering/i],
  ['pest', /病虫|病害|虫害|病斑|虫口|防虫|植保|农药|霉|pest|disease/i],
  ['weather', /天气|气象|降雨|下雨|暴雨|台风|霜冻|寒潮|高温|低温|干旱|weather|rain|forecast/i],
  ['soil', /土壤|水分|墒情|肥力|养分|施肥|氮|磷|钾|盐分|酸碱|\bpH\b|soil/i],
  ['device', /设备|传感器|监测|网关|读数|device|sensor/i],
  ['operations', /经营|成本|收益|利润|预算|生产计划|优先|待办|农事|运营|排产|人力|工时|采收|产量|销售|种植计划|cost|profit/i],
];

const QUESTIONS = {
  irrigation: [
    { question: '做出灌溉判断前，还需要核实哪些数据？', intent: 'irrigation-evidence' },
    '怎样结合土壤水分和天气，安排各地块的灌溉优先级？',
    '如果决定灌溉，之后应如何复核效果和风险？',
    '请把灌溉前的现场检查整理成一份清单。',
    '哪些情况需要先暂停灌溉计划并重新评估？',
    '怎样判断灌溉建议所依据的读数仍然有效？',
  ],
  weather: [
    '这次天气变化会影响哪些农事安排？',
    '天气风险出现前，应该先做哪些现场检查？',
    '如果天气预报发生变化，怎样调整生产计划？',
    '怎样结合地块和作物阶段确定天气应对优先级？',
    '请把天气风险的观察与复核步骤整理成清单。',
    '还缺少哪些信息，才能判断天气对当前作物的影响？',
  ],
  pest: [
    '现场还需要记录哪些症状，才能进一步判断病虫害风险？',
    '请把病虫害排查整理成一份巡田清单。',
    '怎样区分病虫害迹象与缺水、缺肥等相似症状？',
    '哪些变化说明应尽快请农技人员现场确认？',
    '采取防护措施后，应该如何跟踪效果？',
    '目前有哪些不依赖用药判断的日常防护措施？',
  ],
  device: [
    '请按优先级列出设备异常的排查步骤。',
    '设备掉线或读数过期时，哪些农事判断需要暂缓？',
    '设备恢复后，怎样确认读数可信并补做现场复核？',
    '如何区分网络问题、供电问题和传感器本身的故障？',
    '排查设备异常时，应该记录哪些信息？',
    '如何核对相邻设备和现场观察，避免误判异常？',
  ],
  soil: [
    '土壤指标需要结合哪些作物和地块信息一起判断？',
    '如何通过现场复核，判断土壤读数是否可信？',
    '请把土壤异常的观察和复测步骤整理成清单。',
    '如果不同地块的土壤指标差异较大，应先排查什么？',
    '跟踪土壤状况时，哪些变化值得持续记录？',
    '还缺少哪些数据，才能进一步制定土壤管理建议？',
  ],
  operations: [
    '请把这些建议整理成今天的农事优先级清单。',
    '目前哪些信息缺口会影响经营和生产决策？',
    '怎样跟踪这些安排的投入、进度和实际效果？',
    '如果人手或预算有限，应该怎样调整优先顺序？',
    '下一次复盘经营状况时，应重点核对哪些记录？',
    '哪些农事安排需要先完成现场核实再推进？',
  ],
  general: [
    '根据这次分析，我现在最该先核实什么？',
    '请把上面的建议整理成可逐项核对的行动清单。',
    '哪些信息还不确定，会影响下一步判断？',
    '采取建议后，应该怎样记录并复核效果？',
    '如果现场情况与分析不一致，应该怎样重新判断？',
    '请说明这些建议的适用条件和需要注意的风险。',
  ],
};

function text(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function questionKey(value) {
  return text(value).toLowerCase().replace(/^(?:请|麻烦)(?:你)?/, '').replace(/[^\p{L}\p{N}]/gu, '');
}

function topicOf(question) {
  return TOPICS.find(([, pattern]) => pattern.test(question))?.[0];
}

function askedIntents(questions) {
  const intents = new Set();
  // A small, explicit intent rule prevents the common "what data is needed?"
  // loop without pretending that template matching is semantic prediction.
  for (const question of questions) {
    if (topicOf(question) === 'irrigation' && /数据|读数|记录|资料|信息|依据/.test(question)
      && /哪些|什么|哪类|哪几|核对|核查|核实|收集|补充|提供|依据/.test(question)) {
      intents.add('irrigation-evidence');
    }
  }
  return intents;
}

function isContinuation(question) {
  const normalized = questionKey(question);
  // Remove fixed phrases without nested repetition: even a long malformed
  // question must not trigger expensive regular-expression backtracking.
  return normalized.length > 0 && normalized.length <= 80 && normalized.replace(/继续讲|继续说|继续分析|还有什么|还有吗|进一步|能不能|接下来|整理成|再|更|继续|能否|可以|那么|那|然后|还有|这些|上面|这个|它|如何|怎样|怎么|具体|详细|展开|说说|解释|说明|分析|建议|做|处理|呢|吗|一点|一些|一下|下去|给我|需要|注意|什么|把|检查|行动|清单|步骤|好的|好/g, '') === '';
}

export function followUpContext(messages) {
  if (!Array.isArray(messages) || !messages.length) return null;
  const latest = messages.at(-1);
  if (!latest || latest.role !== 'assistant' || latest.pending || latest.temporary || !text(latest.content)) return null;
  if (latest.status && latest.status !== 'completed') return null;
  const recentQuestions = messages.slice(0, -1).filter((message) => message?.role === 'user' && text(message.content))
    .slice(-3).reverse().map((message) => text(message.content));
  if (!recentQuestions.length) return null;
  return {
    messageId: text(latest.id),
    question: recentQuestions[0],
    answer: text(latest.content),
    mode: text(latest.mode),
    recentQuestions,
  };
}

export function contextualFollowUps(messages) {
  const context = followUpContext(messages);
  if (!context) return [];
  let topic = topicOf(context.question);
  // Rule fallback answers often mention every farm topic. They must not override
  // the user's question; only short continuations look back to earlier questions.
  if (!topic && isContinuation(context.question)) {
    topic = context.recentQuestions.slice(1).map(topicOf).find(Boolean);
  }
  const alreadyAsked = new Set(messages.filter((message) => message?.role === 'user').map((message) => questionKey(message.content)));
  const intents = askedIntents(context.recentQuestions);
  return QUESTIONS[topic || 'general']
    .map((item) => typeof item === 'string' ? { question: item } : item)
    .filter((item) => !alreadyAsked.has(questionKey(item.question)) && !intents.has(item.intent))
    .slice(0, 3).map((item) => item.question);
}

export function prepareFollowUp(draft, suggestion, locked = false) {
  const current = typeof draft === 'string' ? draft : '';
  if (locked) return { question: current, changed: false, notice: '' };
  if (current.trim()) return { question: current, changed: false, notice: '输入框里已有你写的问题，已为你保留。可以继续编辑后再发送。' };
  const question = text(suggestion);
  if (!question) return { question: current, changed: false, notice: '' };
  if (question.length > 2000) return { question: current, changed: false, notice: '这条建议过长，请选择其他问题。' };
  return { question, changed: true, notice: '追问已放入输入框，可以编辑后再发送。' };
}

export function normalizeFollowUps(response, context) {
  if (!response || !context || response.source !== 'model') return [];
  if (['conversationId', 'farmId', 'messageId'].some((key) => !text(context[key]) || response[key] !== context[key])) return [];
  if (!Array.isArray(response.suggestions) || response.suggestions.length < 2 || response.suggestions.length > 3) return [];
  const normalized = [];
  const seen = new Set();
  for (const suggestion of response.suggestions) {
    if (typeof suggestion !== 'string' || /[\u0000-\u001f\u007f\u2028\u2029<>]|&(?:lt|gt);|[a-z][a-z\d+.-]*:\/\/|www\.|(?:javascript|data):|\b(?:[a-z\d-]+\.)+[a-z]{2,24}\b/i.test(suggestion)) return [];
    const question = suggestion.trim();
    if (question.length < 4 || question.length > 100) return [];
    const key = questionKey(question);
    if (!key) return [];
    if (!seen.has(key)) {
      seen.add(key);
      normalized.push(question);
    }
  }
  return normalized.length >= 2 ? normalized : [];
}
