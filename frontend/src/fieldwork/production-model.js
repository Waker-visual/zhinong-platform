// 保存结果不明确时保留同一请求标识；只有用户改变提交内容后才开始新的登记。
export function productionSubmission(body, previous, createId = requestId) {
  const signature = JSON.stringify(body);
  return previous?.signature === signature
    ? previous
    : { signature, requestId: createId() };
}

export function eligiblePlantings(plantings, plotId, recordDate) {
  return plantings.filter(
    (planting) =>
      planting.plotId === plotId &&
      recordDate &&
      planting.startDate <= recordDate &&
      recordDate <= planting.endDate,
  );
}

export function eligibleHarvestTasks(tasks, plotId, planting) {
  if (!planting || planting.plotId !== plotId) return [];
  return tasks.filter(
    (task) =>
      task.plotId === plotId &&
      task.taskType === "HARVEST" &&
      task.status === "COMPLETED" &&
      planting.startDate <= task.dueDate &&
      task.dueDate <= planting.endDate,
  );
}
import { requestId } from "../ui/request-id.js";
