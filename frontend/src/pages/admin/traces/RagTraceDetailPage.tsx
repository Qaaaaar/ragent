import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  RefreshCw,
  XCircle,
  Loader2,
  Activity,
  Clock,
  Zap,
  User,
  Calendar,
  Hash
} from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { cn } from "@/lib/utils";
import { getRagTraceDetail, type RagTraceDetail } from "@/services/ragTraceService";
import { getErrorMessage } from "@/utils/error";
import {
  clamp,
  formatDateTime,
  formatDuration,
  normalizeStatus,
  resolveNodeDuration,
  statusBadgeVariant,
  statusLabel,
  toTimestamp,
  type TimelineNode
} from "@/pages/admin/traces/traceUtils";

// ============ 工具函数 ============

const decodeTraceId = (value?: string): string => {
  if (!value) return "";
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
};

const copyToClipboard = (text: string, label: string) => {
  navigator.clipboard.writeText(text).then(() => {
    toast.success(`${label} 已复制`);
  }).catch(() => {
    toast.error("复制失败");
  });
};

// ============ 状态颜色 ============

type StatusType = "success" | "failed" | "running" | "default";

const STATUS_COLORS: Record<StatusType, { dot: string; bar: string }> = {
  success: { dot: "bg-emerald-500", bar: "bg-emerald-400" },
  failed: { dot: "bg-red-500", bar: "bg-red-400" },
  running: { dot: "bg-amber-500", bar: "bg-amber-400" },
  default: { dot: "bg-slate-300", bar: "bg-slate-300" }
};

const getStatusColors = (status?: string | null) => {
  const normalized = normalizeStatus(status) as StatusType | null;
  return STATUS_COLORS[normalized || "default"];
};

type ExtraRecord = Record<string, unknown>;

const asRecord = (value: unknown): ExtraRecord | null => {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  return value as ExtraRecord;
};

const asArray = (value: unknown): unknown[] => Array.isArray(value) ? value : [];

const extraNumber = (value: unknown): number | null => {
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
};

const extraText = (value: unknown): string => {
  if (value === null || value === undefined) return "-";
  return String(value);
};

// ============ 子组件 ============

function MetricItem({
                      icon: Icon,
                      label,
                      value,
                      variant = "default"
                    }: {
  icon: React.ComponentType<{ className?: string }>;
  label: string;
  value: string | number;
  variant?: "default" | "success" | "error" | "warning" | "primary";
}) {
  const styles = {
    default: "text-slate-600",
    success: "text-emerald-600",
    error: "text-red-600",
    warning: "text-amber-600",
    primary: "text-teal-700"
  };

  return (
      <div className="flex items-center gap-2 px-4 py-2">
        <Icon className={cn("h-4 w-4", styles[variant])} />
        <span className={cn("text-lg font-semibold", styles[variant])}>{value}</span>
        <span className="text-xs text-slate-500">{label}</span>
      </div>
  );
}

function TimeScale({ totalMs }: { totalMs: number }) {
  const ticks = [0, 25, 50, 75, 100];
  return (
      <div className="relative h-6 border-b border-slate-200">
        {ticks.map((percent) => (
            <div
                key={percent}
                className="absolute top-0 bottom-0 flex flex-col items-center"
                style={{ left: `${percent}%`, transform: "translateX(-50%)" }}
            >
              <div className="w-px h-2 bg-slate-300" />
              <span className="text-[10px] text-slate-400 mt-0.5">
            {formatDuration((totalMs * percent) / 100)}
          </span>
            </div>
        ))}
      </div>
  );
}

function TraceExtraDataPanel({ detail }: { detail: RagTraceDetail }) {
  const streamData = asRecord(asRecord(detail.run.extraData)?.stream);
  const llmItems = detail.nodes
      .map((node) => ({ node, data: asRecord(asRecord(node.extraData)?.llmStream) }))
      .filter((item): item is { node: RagTraceDetail["nodes"][number]; data: ExtraRecord } => Boolean(item.data));
  const retrievalItems = detail.nodes
      .map((node) => ({ node, data: asRecord(asRecord(node.extraData)?.retrieval) }))
      .filter((item): item is { node: RagTraceDetail["nodes"][number]; data: ExtraRecord } => Boolean(item.data));
  const nodeErrors = detail.nodes.filter((node) =>
      node.errorMessage || asRecord(asRecord(node.extraData)?.llmStream)?.errorMessage
  );

  return (
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-4">
        <Card>
          <CardHeader className="py-3 px-4">
            <CardTitle className="text-sm font-medium text-slate-700">接口耗时</CardTitle>
          </CardHeader>
          <CardContent className="px-4 pb-4 space-y-2 text-sm">
            <div className="flex justify-between gap-3">
              <span className="text-slate-500">总耗时</span>
              <span className="font-medium text-slate-800">{formatDuration(detail.run.durationMs ?? undefined)}</span>
            </div>
            {streamData && (
                <>
                  <div className="flex justify-between gap-3">
                    <span className="text-slate-500">取消</span>
                    <span className="font-medium text-slate-800">{streamData.cancelled ? "是" : "否"}</span>
                  </div>
                  <div className="flex justify-between gap-3">
                    <span className="text-slate-500">回答长度</span>
                    <span className="font-medium text-slate-800">{extraText(streamData.answerLength)}</span>
                  </div>
                </>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="py-3 px-4">
            <CardTitle className="text-sm font-medium text-slate-700">模型耗时</CardTitle>
          </CardHeader>
          <CardContent className="px-4 pb-4 space-y-3 text-sm">
            {llmItems.length === 0 ? (
                <p className="text-slate-400">暂无模型耗时</p>
            ) : llmItems.map(({ node, data }) => (
                <div key={node.nodeId} className="border-b border-slate-100 last:border-0 pb-2 last:pb-0">
                  <div className="flex justify-between gap-3">
                    <span className="text-slate-700 truncate">{node.nodeName || node.methodName || node.nodeId}</span>
                    <span className="font-medium text-slate-800">{formatDuration(extraNumber(data.durationMs))}</span>
                  </div>
                  <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-slate-500">
                    <span>首包 {formatDuration(extraNumber(data.firstTokenLatencyMs))}</span>
                    <span>状态 {extraText(data.status)}</span>
                    <span>输出 {extraText(data.contentLength)}</span>
                  </div>
                  {data.errorMessage && <p className="mt-1 text-xs text-red-600">{extraText(data.errorMessage)}</p>}
                </div>
            ))}
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="py-3 px-4">
            <CardTitle className="text-sm font-medium text-slate-700">异常信息</CardTitle>
          </CardHeader>
          <CardContent className="px-4 pb-4 space-y-2 text-sm">
            {nodeErrors.length === 0 && !detail.run.errorMessage ? (
                <p className="text-slate-400">暂无异常</p>
            ) : (
                <>
                  {detail.run.errorMessage && <p className="text-red-600">{detail.run.errorMessage}</p>}
                  {nodeErrors.map((node) => {
                    const llm = asRecord(asRecord(node.extraData)?.llmStream);
                    return (
                        <div key={node.nodeId} className="text-xs">
                          <span className="font-medium text-slate-700">{node.nodeName || node.methodName || node.nodeId}: </span>
                          <span className="text-red-600">{node.errorMessage || extraText(llm?.errorMessage)}</span>
                        </div>
                    );
                  })}
                </>
            )}
          </CardContent>
        </Card>

        {retrievalItems.map(({ node, data }) => (
            <Card key={node.nodeId} className="xl:col-span-3">
              <CardHeader className="py-3 px-4">
                <CardTitle className="text-sm font-medium text-slate-700">召回结果</CardTitle>
              </CardHeader>
              <CardContent className="px-4 pb-4 space-y-3 text-sm">
                <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-slate-500">
                  <span>节点 {node.nodeName || node.methodName || node.nodeId}</span>
                  <span>TopK {extraText(data.topK)}</span>
                  <span>通道 {extraText(data.channelCount)}</span>
                  <span>最终 Chunk {extraText(data.finalChunkCount)}</span>
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-2">
                  {asArray(data.channels).map((item, index) => {
                    const channel = asRecord(item) || {};
                    return (
                        <div key={index} className="rounded border border-slate-100 px-3 py-2">
                          <div className="flex justify-between gap-3">
                            <span className="font-medium text-slate-700">{extraText(channel.name)}</span>
                            <span className="text-slate-500">{formatDuration(extraNumber(channel.latencyMs))}</span>
                          </div>
                          <div className="mt-1 text-xs text-slate-500">Chunk {extraText(channel.chunkCount)} · {extraText(channel.type)}</div>
                          {channel.errorMessage && <p className="mt-1 text-xs text-red-600">{extraText(channel.errorMessage)}</p>}
                        </div>
                    );
                  })}
                </div>
                <div className="space-y-2">
                  {asArray(data.chunks).slice(0, 20).map((item, index) => {
                    const chunk = asRecord(item) || {};
                    return (
                        <div key={index} className="rounded bg-slate-50 px-3 py-2">
                          <div className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-slate-500">
                            <span className="font-mono">{extraText(chunk.id)}</span>
                            <span>score {extraText(chunk.score)}</span>
                          </div>
                          <p className="mt-1 text-xs text-slate-700 whitespace-pre-wrap">{extraText(chunk.textPreview)}</p>
                        </div>
                    );
                  })}
                </div>
              </CardContent>
            </Card>
        ))}
      </div>
  );
}

function WaterfallRow({
                        node,
                        nodeDisplayName,
                        nodeStatus,
                        isTopSlowest
                      }: {
  node: TimelineNode & {
    depthValue: number;
    resolvedDurationMs: number;
    offsetMs: number;
    leftPercent: number;
    widthPercent: number;
  };
  nodeDisplayName: string;
  nodeStatus: string | null;
  isTopSlowest?: boolean;
}) {
  const colors = getStatusColors(nodeStatus);

  return (
      <div className={cn(
          "grid grid-cols-[minmax(180px,1fr)_120px_2fr_100px] gap-4 px-4 py-2.5 transition-colors group",
          "hover:bg-slate-50/80",
          isTopSlowest && "bg-amber-50/40"
      )}>
        <div
            className="flex items-center gap-2 min-w-0"
            style={{ paddingLeft: `${Math.min(node.depthValue, 6) * 16}px` }}
        >
        <span className={cn(
            "h-2 w-2 rounded-full shrink-0 transition-transform group-hover:scale-125",
            colors.dot
        )} />
          <span className="text-sm text-slate-700 truncate" title={nodeDisplayName}>
          {nodeDisplayName}
        </span>
          {isTopSlowest && (
              <Zap className="h-3 w-3 text-amber-500 shrink-0" />
          )}
        </div>

        <div className="flex items-center">
        <span
            className="text-xs text-slate-500 bg-slate-100 px-2 py-0.5 rounded truncate"
            title={node.nodeType || "-"}
        >
          {node.nodeType || "-"}
        </span>
        </div>

        <div className="flex items-center">
          <div className="relative w-full h-6 bg-slate-50 rounded overflow-hidden">
            {[25, 50, 75].map(p => (
                <div
                    key={p}
                    className="absolute top-0 bottom-0 w-px bg-slate-200"
                    style={{ left: `${p}%` }}
                />
            ))}
            <div
                className={cn(
                    "absolute top-1 bottom-1 rounded transition-all",
                    colors.bar,
                    "group-hover:brightness-110"
                )}
                style={{
                  left: `${node.leftPercent}%`,
                  width: `${Math.max(node.widthPercent, 0.5)}%`,
                  minWidth: "4px"
                }}
                title={`${nodeDisplayName} - ${formatDuration(node.resolvedDurationMs)}`}
            />
          </div>
        </div>

        <div className="text-right">
          <p className="text-sm font-medium text-slate-700">
            {formatDuration(node.resolvedDurationMs)}
          </p>
          <p className="text-[10px] text-slate-400">
            @{formatDuration(node.offsetMs)}
          </p>
        </div>
      </div>
  );
}

// ============ 主组件 ============

export function RagTraceDetailPage() {
  const params = useParams<{ traceId: string }>();
  const traceId = decodeTraceId(params.traceId);
  const detailRequestRef = useRef(0);
  const [detail, setDetail] = useState<RagTraceDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const loadDetail = async (nextTraceId: string) => {
    if (!nextTraceId) return;
    const requestId = ++detailRequestRef.current;
    setDetailLoading(true);
    try {
      const result = await getRagTraceDetail(nextTraceId);
      if (detailRequestRef.current !== requestId) return;
      setDetail(result);
    } catch (error) {
      if (detailRequestRef.current !== requestId) return;
      toast.error(getErrorMessage(error, "加载 Agent 处理链路详情失败"));
      console.error(error);
      setDetail(null);
    } finally {
      if (detailRequestRef.current !== requestId) return;
      setDetailLoading(false);
    }
  };

  useEffect(() => {
    if (!traceId) {
      detailRequestRef.current += 1;
      setDetail(null);
      setDetailLoading(false);
      return;
    }
    loadDetail(traceId);
  }, [traceId]);

  const selectedRun = detail?.run || null;

  const timeline = useMemo(() => {
    const nodes = detail?.nodes || [];
    if (!nodes.length) return { totalWindowMs: 0, nodes: [] as any[] };

    const normalized = nodes.map((node) => {
      const startTs = toTimestamp(node.startTime);
      const endTs = toTimestamp(node.endTime);
      const resolvedDurationMs = resolveNodeDuration(node);
      const depthValue = Math.max(0, Number(node.depth ?? 0));
      const resolvedStartTs = startTs ?? 0;
      const resolvedEndTs = endTs ?? (resolvedStartTs > 0 ? resolvedStartTs + resolvedDurationMs : 0);
      return { ...node, depthValue, resolvedDurationMs, startTs: resolvedStartTs, endTs: resolvedEndTs };
    });

    const withTime = normalized.filter((item) => item.startTs > 0);
    const baseStart = withTime.length
        ? withTime.reduce((min, item) => Math.min(min, item.startTs), withTime[0].startTs)
        : Date.now();
    const maxEnd = withTime.length
        ? withTime.reduce((max, item) => Math.max(max, item.endTs || item.startTs), withTime[0].endTs || withTime[0].startTs)
        : baseStart;
    const runDuration = Number(selectedRun?.durationMs ?? 0);
    const windowDuration = Math.max(runDuration > 0 ? runDuration : maxEnd - baseStart, 1);

    const rows = normalized
        .sort((a, b) => a.startTs - b.startTs || a.depthValue - b.depthValue)
        .map((node) => {
          const offsetMs = node.startTs > 0 ? Math.max(0, node.startTs - baseStart) : 0;
          const leftPercent = clamp((offsetMs / windowDuration) * 100, 0, 99.2);
          const widthPercent = clamp(
              (Math.max(node.resolvedDurationMs, 1) / windowDuration) * 100,
              0.8,
              100 - leftPercent
          );
          return { ...node, offsetMs, leftPercent, widthPercent };
        });

    return { totalWindowMs: windowDuration, nodes: rows };
  }, [detail?.nodes, selectedRun?.durationMs]);

  const stats = useMemo(() => {
    const nodes = detail?.nodes || [];
    const total = nodes.length;
    const failed = nodes.filter((n) => normalizeStatus(n.status) === "failed").length;
    const success = nodes.filter((n) => normalizeStatus(n.status) === "success").length;
    const running = nodes.filter((n) => normalizeStatus(n.status) === "running").length;

    const durations = nodes.map(n => resolveNodeDuration(n));
    const avgDuration = total > 0 ? Math.round(durations.reduce((a, b) => a + b, 0) / total) : 0;

    const sortedByDuration = [...nodes].sort((a, b) => resolveNodeDuration(b) - resolveNodeDuration(a));
    const topSlowestId = sortedByDuration[0]?.nodeId;

    return { total, failed, success, running, avgDuration, topSlowestId };
  }, [detail?.nodes]);

  if (detailLoading) {
    return (
        <div className="min-h-[400px] flex items-center justify-center">
          <div className="flex flex-col items-center gap-3 text-slate-500">
            <Loader2 className="h-8 w-8 animate-spin" />
            <p>加载 Agent 处理链路详情中...</p>
          </div>
        </div>
    );
  }

  if (!traceId || !selectedRun) {
    return (
        <div className="space-y-6">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-1.5 text-sm">
              <Link to="/admin/traces" className="text-slate-500 hover:text-slate-700">
                Agent 处理链路
              </Link>
              <span className="text-slate-300">/</span>
              <span className="text-slate-400">详情</span>
            </div>
            <Button
                asChild
                variant="outline"
                size="sm"
                className="text-slate-600 hover:text-slate-800"
            >
              <Link to="/admin/traces">
                <ArrowLeft className="mr-1.5 h-4 w-4" />
                返回列表
              </Link>
            </Button>
          </div>
          <div className="min-h-[300px] flex items-center justify-center">
            <div className="text-center text-slate-500">
              <AlertTriangle className="h-12 w-12 mx-auto mb-4 text-slate-300" />
              <p>{!traceId ? "缺少 Trace Id" : "暂无数据"}</p>
            </div>
          </div>
        </div>
    );
  }

  return (
      <div className="space-y-4 pb-8">
        {/* 标题栏 */}
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-1.5 text-sm">
              <Link
                  to="/admin/traces"
                  className="text-slate-500 hover:text-slate-700 transition-colors"
              >
                Agent 处理链路列表
              </Link>
              <span className="text-slate-300">/</span>
            </div>
            <div className="flex items-center gap-2">
              <h1 className="text-lg font-semibold text-slate-900">
                {selectedRun.traceName || "未命名处理链路"}
              </h1>
              <Badge variant={statusBadgeVariant(selectedRun.status)} className="text-xs">
                {statusLabel(selectedRun.status)}
              </Badge>
            </div>
          </div>

          {/* 右侧按钮组 */}
          <div className="flex items-center gap-2">
            <Button
                asChild
                variant="outline"
                size="sm"
                className="text-slate-600 hover:text-slate-800"
            >
              <Link to="/admin/traces">
                <ArrowLeft className="mr-1.5 h-4 w-4" />
                返回列表
              </Link>
            </Button>
            <Button
                variant="outline"
                size="sm"
                className="text-slate-600 hover:text-slate-800"
                onClick={() => loadDetail(traceId)}
                disabled={detailLoading}
            >
              <RefreshCw className={cn("mr-1.5 h-4 w-4", detailLoading && "animate-spin")} />
              刷新
            </Button>
          </div>
        </div>

        {/* 元信息 */}
        <div className="flex items-center gap-4 text-xs text-slate-500">
        <span
            className="font-mono cursor-pointer hover:text-slate-700 flex items-center gap-1 transition-colors"
            onClick={() => copyToClipboard(traceId, "Trace Id")}
            title="点击复制 Trace Id"
        >
          <Hash className="h-3 w-3" />
          {traceId.length > 28 ? `${traceId.slice(0, 12)}...${traceId.slice(-8)}` : traceId}
        </span>
          <span className="flex items-center gap-1">
          <Calendar className="h-3 w-3" />
            {formatDateTime(selectedRun.startTime ?? undefined)}
        </span>
          {(selectedRun.username || selectedRun.userId) && (
              <span className="flex items-center gap-1">
            <User className="h-3 w-3" />
                {selectedRun.username || selectedRun.userId}
          </span>
          )}
        </div>

        {/* 错误提示 */}
        {selectedRun.errorMessage && (
            <div className="flex items-start gap-3 p-3 bg-red-50 border border-red-200 rounded-lg">
              <AlertTriangle className="h-4 w-4 text-red-500 shrink-0 mt-0.5" />
              <div className="text-sm">
                <span className="font-medium text-red-800">Agent 执行出错：</span>
                <span className="text-red-600 ml-1">{selectedRun.errorMessage}</span>
              </div>
            </div>
        )}

        {/* 指标条 */}
        <div className="flex items-center bg-slate-50 rounded-lg border border-slate-200 divide-x divide-slate-200">
          <MetricItem
              icon={Clock}
              label="总耗时"
              value={formatDuration(selectedRun.durationMs ?? undefined)}
              variant="primary"
          />
          <MetricItem
              icon={Activity}
              label="节点"
              value={stats.total}
          />
          <MetricItem
              icon={CheckCircle2}
              label="成功"
              value={stats.success}
              variant="success"
          />
          <MetricItem
              icon={XCircle}
              label="失败"
              value={stats.failed}
              variant={stats.failed > 0 ? "error" : "default"}
          />
          {stats.running > 0 && (
              <MetricItem
                  icon={Loader2}
                  label="运行中"
                  value={stats.running}
                  variant="warning"
              />
          )}
          <MetricItem
              icon={Zap}
              label="平均耗时"
              value={formatDuration(stats.avgDuration)}
          />
        </div>

        <TraceExtraDataPanel detail={detail} />

        {/* 瀑布图 */}
        <Card>
          <CardHeader className="py-3 px-4">
            <div className="flex items-center justify-between">
              <CardTitle className="text-sm font-medium text-slate-700">
                Agent 执行时序
              </CardTitle>
              <span className="text-xs text-slate-500">
              窗口 {formatDuration(timeline.totalWindowMs)}
            </span>
            </div>
          </CardHeader>
          <CardContent className="p-0">
            {timeline.nodes.length === 0 ? (
                <div className="py-16 text-center text-slate-400">
                  <Activity className="h-10 w-10 mx-auto mb-3 opacity-50" />
                  <p>暂无节点记录</p>
                </div>
            ) : (
                <div>
                  <div className="grid grid-cols-[minmax(180px,1fr)_120px_2fr_100px] gap-4 px-4 py-2 text-xs font-medium text-slate-500 bg-slate-50 border-y border-slate-100">
                    <span>节点</span>
                    <span>类型</span>
                    <span>时间线</span>
                    <span className="text-right">耗时</span>
                  </div>

                  <div className="grid grid-cols-[minmax(180px,1fr)_120px_2fr_100px] gap-4 px-4 bg-white">
                    <div />
                    <div />
                    <TimeScale totalMs={timeline.totalWindowMs} />
                    <div />
                  </div>

                  <div className="divide-y divide-slate-50">
                    {timeline.nodes.map((node) => {
                      const nodeDisplayName = node.nodeName || node.methodName || node.nodeId;
                      const nodeStatus = normalizeStatus(node.status);
                      const isTopSlowest = node.nodeId === stats.topSlowestId;

                      return (
                          <WaterfallRow
                              key={node.nodeId}
                              node={node}
                              nodeDisplayName={nodeDisplayName}
                              nodeStatus={nodeStatus}
                              isTopSlowest={isTopSlowest}
                          />
                      );
                    })}
                  </div>
                </div>
            )}
          </CardContent>
        </Card>
      </div>
  );
}
