import { api } from "@/services/api";

export async function stopTask(taskId: string) {
  return api.post<void>(`/agent/v1/stop?taskId=${encodeURIComponent(taskId)}`);
}
