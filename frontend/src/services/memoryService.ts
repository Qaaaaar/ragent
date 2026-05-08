import { api } from "@/services/api";

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface MemoryItem {
  id: string;
  scopeType: "USER" | "ORG";
  ownerUserId?: string | null;
  sourceUserId?: string | null;
  memoryLevel: "WORKING" | "LONG_TERM";
  memoryType: string;
  title: string;
  content: string;
  keywords?: string[];
  sourceConversationId?: string | null;
  sourceMessageIds?: string[];
  confidence?: number | null;
  importance?: number | null;
  status: "ACTIVE" | "PENDING_REVIEW" | "REJECTED" | "ARCHIVED";
  expireTime?: string | null;
  lastAccessTime?: string | null;
  accessCount?: number | null;
  createTime?: string | null;
  updateTime?: string | null;
}

export interface MemoryQuery {
  current?: number;
  size?: number;
  keyword?: string;
  scopeType?: string;
  memoryLevel?: string;
  memoryType?: string;
  status?: string;
}

export interface MemoryPayload {
  scopeType?: string;
  ownerUserId?: string | null;
  memoryLevel?: string;
  memoryType?: string;
  title?: string;
  content?: string;
  keywords?: string[];
  confidence?: number;
  importance?: number;
  status?: string;
  expireTime?: string | null;
}

export async function getMemories(query: MemoryQuery): Promise<PageResult<MemoryItem>> {
  return api.get<PageResult<MemoryItem>, PageResult<MemoryItem>>("/memories", { params: query });
}

export async function createMemory(payload: MemoryPayload): Promise<string> {
  return api.post<string, string>("/memories", payload);
}

export async function updateMemory(id: string, payload: MemoryPayload): Promise<void> {
  await api.put(`/memories/${id}`, payload);
}

export async function deleteMemory(id: string): Promise<void> {
  await api.delete(`/memories/${id}`);
}

export async function updateMemoryStatus(id: string, status: string): Promise<void> {
  await api.put(`/memories/${id}/status`, { status });
}

export async function reviewMemory(id: string, approved: boolean): Promise<void> {
  await api.post(`/memories/${id}/review`, { approved });
}

export async function getUserMemories(query: MemoryQuery): Promise<PageResult<MemoryItem>> {
  return api.get<PageResult<MemoryItem>, PageResult<MemoryItem>>("/user/memories", { params: query });
}

export async function createUserMemory(payload: MemoryPayload): Promise<string> {
  return api.post<string, string>("/user/memories", payload);
}

export async function updateUserMemory(id: string, payload: MemoryPayload): Promise<void> {
  await api.put(`/user/memories/${id}`, payload);
}

export async function deleteUserMemory(id: string): Promise<void> {
  await api.delete(`/user/memories/${id}`);
}
