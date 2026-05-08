import { useCallback, useEffect, useState } from "react";
import { Check, Pencil, Plus, RefreshCw, Trash2, X } from "lucide-react";
import { toast } from "sonner";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import type { MemoryItem, PageResult } from "@/services/memoryService";
import {
  createMemory,
  deleteMemory,
  getMemories,
  reviewMemory,
  updateMemory,
  updateMemoryStatus
} from "@/services/memoryService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 10;

const emptyForm = {
  scopeType: "USER",
  ownerUserId: "",
  memoryLevel: "LONG_TERM",
  memoryType: "preference",
  title: "",
  content: "",
  keywords: "",
  confidence: 1,
  importance: 50,
  status: "ACTIVE"
};

export function AdminMemoryPage() {
  const [pageData, setPageData] = useState<PageResult<MemoryItem> | null>(null);
  const [loading, setLoading] = useState(true);
  const [pageNo, setPageNo] = useState(1);
  const [keywordInput, setKeywordInput] = useState("");
  const [keyword, setKeyword] = useState("");
  const [scopeType, setScopeType] = useState("ALL");
  const [memoryLevel, setMemoryLevel] = useState("ALL");
  const [status, setStatus] = useState("ALL");
  const [deleteTarget, setDeleteTarget] = useState<MemoryItem | null>(null);
  const [dialogState, setDialogState] = useState<{ open: boolean; mode: "create" | "edit"; item: MemoryItem | null }>({
    open: false,
    mode: "create",
    item: null
  });
  const [form, setForm] = useState(emptyForm);

  const loadData = useCallback(async (current = pageNo) => {
    try {
      setLoading(true);
      const data = await getMemories({
        current,
        size: PAGE_SIZE,
        keyword: keyword || undefined,
        scopeType: scopeType === "ALL" ? undefined : scopeType,
        memoryLevel: memoryLevel === "ALL" ? undefined : memoryLevel,
        status: status === "ALL" ? undefined : status
      });
      setPageData(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载记忆失败"));
    } finally {
      setLoading(false);
    }
  }, [keyword, memoryLevel, pageNo, scopeType, status]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  useEffect(() => {
    if (!dialogState.open) {
      setForm(emptyForm);
      return;
    }
    if (dialogState.mode === "edit" && dialogState.item) {
      const item = dialogState.item;
      setForm({
        scopeType: item.scopeType || "USER",
        ownerUserId: item.ownerUserId || "",
        memoryLevel: item.memoryLevel || "LONG_TERM",
        memoryType: item.memoryType || "preference",
        title: item.title || "",
        content: item.content || "",
        keywords: (item.keywords || []).join(", "),
        confidence: item.confidence ?? 1,
        importance: item.importance ?? 50,
        status: item.status || "ACTIVE"
      });
    }
  }, [dialogState]);

  const records = pageData?.records || [];

  const handleSubmit = async () => {
    const payload = {
      scopeType: form.scopeType,
      ownerUserId: form.scopeType === "USER" ? form.ownerUserId.trim() || null : null,
      memoryLevel: form.memoryLevel,
      memoryType: form.memoryType.trim() || "fact",
      title: form.title.trim(),
      content: form.content.trim(),
      keywords: form.keywords.split(",").map((item) => item.trim()).filter(Boolean),
      confidence: Number(form.confidence),
      importance: Number(form.importance),
      status: form.status
    };
    if (!payload.title || !payload.content) {
      toast.error("请输入标题和内容");
      return;
    }
    if (payload.scopeType === "USER" && !payload.ownerUserId) {
      toast.error("用户记忆需要填写用户 ID");
      return;
    }
    try {
      if (dialogState.mode === "create") {
        await createMemory(payload);
        toast.success("记忆已创建");
        setPageNo(1);
      } else if (dialogState.item) {
        await updateMemory(dialogState.item.id, payload);
        toast.success("记忆已更新");
      }
      setDialogState({ open: false, mode: "create", item: null });
      await loadData(dialogState.mode === "create" ? 1 : pageNo);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存记忆失败"));
    }
  };

  const formatDate = (value?: string | null) => (value ? new Date(value).toLocaleString("zh-CN") : "-");

  const statusBadge = (value: string) => {
    const variant = value === "ACTIVE" ? "default" : value === "PENDING_REVIEW" ? "secondary" : "outline";
    return <Badge variant={variant}>{value}</Badge>;
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">记忆治理</h1>
          <p className="admin-page-subtitle">管理用户级与组织级中长期记忆</p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={keywordInput}
            onChange={(event) => setKeywordInput(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                setPageNo(1);
                setKeyword(keywordInput.trim());
              }
            }}
            placeholder="搜索标题、内容、类型"
            className="w-[220px]"
          />
          <Select value={scopeType} onValueChange={(value) => { setPageNo(1); setScopeType(value); }}>
            <SelectTrigger className="w-[130px]"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">全部范围</SelectItem>
              <SelectItem value="USER">用户</SelectItem>
              <SelectItem value="ORG">组织</SelectItem>
            </SelectContent>
          </Select>
          <Select value={memoryLevel} onValueChange={(value) => { setPageNo(1); setMemoryLevel(value); }}>
            <SelectTrigger className="w-[150px]"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">全部层级</SelectItem>
              <SelectItem value="WORKING">WORKING</SelectItem>
              <SelectItem value="LONG_TERM">LONG_TERM</SelectItem>
            </SelectContent>
          </Select>
          <Select value={status} onValueChange={(value) => { setPageNo(1); setStatus(value); }}>
            <SelectTrigger className="w-[150px]"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="ALL">全部状态</SelectItem>
              <SelectItem value="ACTIVE">ACTIVE</SelectItem>
              <SelectItem value="PENDING_REVIEW">PENDING</SelectItem>
              <SelectItem value="REJECTED">REJECTED</SelectItem>
              <SelectItem value="ARCHIVED">ARCHIVED</SelectItem>
            </SelectContent>
          </Select>
          <Button variant="outline" onClick={() => { setPageNo(1); setKeyword(keywordInput.trim()); }}>
            搜索
          </Button>
          <Button variant="outline" onClick={() => loadData()}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={() => setDialogState({ open: true, mode: "create", item: null })}>
            <Plus className="mr-2 h-4 w-4" />
            新增记忆
          </Button>
        </div>
      </div>

      <Card>
        <CardContent className="pt-6">
          {loading ? (
            <div className="py-8 text-center text-muted-foreground">加载中...</div>
          ) : records.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground">暂无记忆</div>
          ) : (
            <Table className="min-w-[1180px]">
              <TableHeader>
                <TableRow>
                  <TableHead>标题</TableHead>
                  <TableHead>范围</TableHead>
                  <TableHead>层级</TableHead>
                  <TableHead>类型</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>置信度</TableHead>
                  <TableHead>访问</TableHead>
                  <TableHead>更新时间</TableHead>
                  <TableHead className="w-[240px]">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {records.map((item) => (
                  <TableRow key={item.id}>
                    <TableCell>
                      <div className="max-w-[280px]">
                        <div className="truncate font-medium" title={item.title}>{item.title}</div>
                        <div className="line-clamp-2 text-xs text-slate-500" title={item.content}>{item.content}</div>
                      </div>
                    </TableCell>
                    <TableCell>{item.scopeType}{item.ownerUserId ? ` / ${item.ownerUserId}` : ""}</TableCell>
                    <TableCell>{item.memoryLevel}</TableCell>
                    <TableCell>{item.memoryType}</TableCell>
                    <TableCell>{statusBadge(item.status)}</TableCell>
                    <TableCell>{Math.round((item.confidence || 0) * 100)}%</TableCell>
                    <TableCell>{item.accessCount || 0}</TableCell>
                    <TableCell>{formatDate(item.updateTime)}</TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-2">
                        <Button variant="outline" size="sm" onClick={() => setDialogState({ open: true, mode: "edit", item })}>
                          <Pencil className="mr-1 h-4 w-4" />
                          编辑
                        </Button>
                        {item.status === "PENDING_REVIEW" ? (
                          <>
                            <Button variant="outline" size="sm" onClick={() => reviewMemory(item.id, true).then(() => loadData())}>
                              <Check className="mr-1 h-4 w-4" />
                              通过
                            </Button>
                            <Button variant="outline" size="sm" onClick={() => reviewMemory(item.id, false).then(() => loadData())}>
                              <X className="mr-1 h-4 w-4" />
                              拒绝
                            </Button>
                          </>
                        ) : null}
                        {item.status !== "ARCHIVED" ? (
                          <Button variant="ghost" size="sm" onClick={() => updateMemoryStatus(item.id, "ARCHIVED").then(() => loadData())}>
                            归档
                          </Button>
                        ) : null}
                        <Button variant="ghost" size="sm" className="text-destructive hover:text-destructive" onClick={() => setDeleteTarget(item)}>
                          <Trash2 className="mr-1 h-4 w-4" />
                          删除
                        </Button>
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      {pageData ? (
        <div className="mt-4 flex items-center justify-between text-sm text-slate-500">
          <span>共 {pageData.total} 条</span>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" disabled={pageData.current <= 1} onClick={() => setPageNo((prev) => Math.max(1, prev - 1))}>
              上一页
            </Button>
            <span>{pageData.current} / {pageData.pages || 1}</span>
            <Button variant="outline" size="sm" disabled={pageData.current >= pageData.pages} onClick={() => setPageNo((prev) => Math.min(pageData.pages || 1, prev + 1))}>
              下一页
            </Button>
          </div>
        </div>
      ) : null}

      <Dialog open={dialogState.open} onOpenChange={(open) => setDialogState((prev) => ({ ...prev, open }))}>
        <DialogContent className="sm:max-w-[620px]">
          <DialogHeader>
            <DialogTitle>{dialogState.mode === "create" ? "新增记忆" : "编辑记忆"}</DialogTitle>
            <DialogDescription>组织记忆会对所有用户生效，用户记忆仅作用于指定用户。</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4">
            <div className="grid grid-cols-2 gap-4">
              <Select value={form.scopeType} onValueChange={(value) => setForm((prev) => ({ ...prev, scopeType: value }))}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="USER">用户记忆</SelectItem>
                  <SelectItem value="ORG">组织记忆</SelectItem>
                </SelectContent>
              </Select>
              <Input
                value={form.ownerUserId}
                onChange={(event) => setForm((prev) => ({ ...prev, ownerUserId: event.target.value }))}
                disabled={form.scopeType === "ORG"}
                placeholder="用户 ID"
              />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <Select value={form.memoryLevel} onValueChange={(value) => setForm((prev) => ({ ...prev, memoryLevel: value }))}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="WORKING">WORKING</SelectItem>
                  <SelectItem value="LONG_TERM">LONG_TERM</SelectItem>
                </SelectContent>
              </Select>
              <Input value={form.memoryType} onChange={(event) => setForm((prev) => ({ ...prev, memoryType: event.target.value }))} placeholder="memoryType" />
            </div>
            <Input value={form.title} onChange={(event) => setForm((prev) => ({ ...prev, title: event.target.value }))} placeholder="标题" />
            <Textarea value={form.content} onChange={(event) => setForm((prev) => ({ ...prev, content: event.target.value }))} placeholder="记忆内容" />
            <Input value={form.keywords} onChange={(event) => setForm((prev) => ({ ...prev, keywords: event.target.value }))} placeholder="关键词，用英文逗号分隔" />
            <div className="grid grid-cols-3 gap-4">
              <Input type="number" min={0} max={1} step={0.01} value={form.confidence} onChange={(event) => setForm((prev) => ({ ...prev, confidence: Number(event.target.value) }))} />
              <Input type="number" min={0} max={100} value={form.importance} onChange={(event) => setForm((prev) => ({ ...prev, importance: Number(event.target.value) }))} />
              <Select value={form.status} onValueChange={(value) => setForm((prev) => ({ ...prev, status: value }))}>
                <SelectTrigger><SelectValue /></SelectTrigger>
                <SelectContent>
                  <SelectItem value="ACTIVE">ACTIVE</SelectItem>
                  <SelectItem value="PENDING_REVIEW">PENDING</SelectItem>
                  <SelectItem value="REJECTED">REJECTED</SelectItem>
                  <SelectItem value="ARCHIVED">ARCHIVED</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setDialogState({ open: false, mode: "create", item: null })}>取消</Button>
            <Button onClick={handleSubmit}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog open={!!deleteTarget} onOpenChange={() => setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认删除记忆？</AlertDialogTitle>
            <AlertDialogDescription>删除后该记忆不会再参与后续召回。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground"
              onClick={() => {
                if (!deleteTarget) return;
                deleteMemory(deleteTarget.id).then(() => {
                  setDeleteTarget(null);
                  loadData();
                });
              }}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
