import { api } from "@/services/api";

export async function stopTask(taskId: string) {
  return api.post<void>(`/agent/v1/stop?taskId=${encodeURIComponent(taskId)}`);
}

export async function submitFeedback(messageId: string, vote: number) {
  return api.post<void>(`/conversations/messages/${messageId}/feedback`, {
    vote
  });
}
