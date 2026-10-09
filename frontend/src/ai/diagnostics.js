// 模型网关诊断码 -> 中文提示文案，供农场助手对话与平台管理员的模型服务设置面板共用。
// 诊断码来自后端 LlmGateway.diagnostic()：云端请求成功/失败的脱敏原因，从不包含密钥或上游响应正文。
export const diagnosticLabels = {
  OK: "连接正常",
  NOT_CONFIGURED: "模型服务尚未配置",
  AUTH_FAILED: "模型认证失败，请联系平台管理员更换凭据",
  BALANCE_REQUIRED: "模型账户余额不足",
  RATE_LIMITED: "模型请求过于频繁，请稍后重试",
  TIMEOUT: "模型服务响应超时",
  BUSY: "模型服务繁忙",
  UNAVAILABLE: "模型服务暂不可用",
  SERVICE_ERROR: "模型服务返回错误",
  EMPTY_RESPONSE: "模型未返回有效回答",
};

export function diagnosticLabel(code) {
  return diagnosticLabels[code] || "模型暂不可用";
}
