import { api } from "@/services/api";

export interface ChangePasswordPayload {
  currentPassword: string;
  newPassword: string;
}

export async function changePassword(payload: ChangePasswordPayload): Promise<void> {
  await api.put("/user/password", payload);
}
