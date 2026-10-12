// Presentation follows the existing conversation run; the companion makes no requests.
export function companionPresentation({ loading = false, sending = false, run = null, error = '', outcome = '' } = {}) {
  if (run?.status === 'cancelled' || outcome === 'stopped') {
    return { state: 'stopped', message: run?.adapter === 'sync'
      ? '已停止等待，问题留在输入框里。这次请求可能仍在后台处理，可稍后查看对话。'
      : '先停在这里，问题留在输入框里。改好后，我们再接着聊。' };
  }
  if (run?.status === 'error' || error) {
    return { state: 'error', message: '刚才没能顺利完成。页面中保留了具体提示，你可以检查后重试。' };
  }
  if (sending) {
    if (run?.status === 'completed') return { state: 'loading', message: '回答已生成，正在同步对话记录。马上就好。' };
    return run?.text?.trim()
      ? { state: 'answering', message: '回答正在展开，我会把已有依据和缺少的信息一起说明。' }
      : { state: 'thinking', message: '正在准备这次回答，稍等一下。需要修改问题时，可以先停止生成。' };
  }
  if (loading) return { state: 'loading', message: '正在读取当前农场资料，稍等一下就能开始聊。' };
  if (outcome === 'completed') return { state: 'done', message: '回答整理好了！先核对对话中的依据，再决定下一步。' };
  return { state: 'idle', message: '我是小禾，陪你把农事理清楚。点点我，或选个话题开始聊。' };
}

export const companionTopics = [
  { label: '聊聊今日农事', question: '请结合当前农场的作物、待办和现场问题，梳理今天优先做的三件事，说明依据和缺失信息。' },
  { label: '核查设备异常', question: '请核查当前农场的设备异常、掉线和过期读数，按优先级给出检查步骤，不要把缺测当正常。' },
  { label: '看看灌溉依据', question: '当前地块是否需要灌溉？请结合最新有效土壤水分与种植资料，说明依据、缺失信息和现场复核步骤。' },
];

export function prepareCompanionQuestion(draft, topic, locked = false) {
  const current = typeof draft === 'string' ? draft : '';
  if (locked) return { question: current, changed: false, notice: '' };
  if (current.trim()) return { question: current, changed: false, notice: '输入框里已有你写的问题，我先帮你保留。可以继续编辑，再发送给我。' };
  if (!topic?.question) return { question: current, changed: false, notice: '' };
  return { question: topic.question, changed: true, notice: '话题放进输入框啦。可以改成你关心的问题，准备好再发送。' };
}
