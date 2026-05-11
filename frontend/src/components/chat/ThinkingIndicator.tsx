import { Brain, Loader2 } from "lucide-react";

interface ThinkingIndicatorProps {
  content?: string;
  duration?: number;
}

export function ThinkingIndicator({ content, duration }: ThinkingIndicatorProps) {
  return (
    <div className="rounded-lg border border-[#99F6E4] bg-[#CCFBF1] p-4">
      <div className="flex items-center gap-2 text-[#0F766E]">
        <Loader2 className="h-4 w-4 animate-spin" />
        <span className="text-sm font-medium">正在检索资料并规划回答...</span>
        {duration ? (
          <span className="text-xs text-[#0F766E] bg-[#99F6E4] px-2 py-0.5 rounded-full">
            {duration}秒
          </span>
        ) : null}
      </div>
      <div className="mt-3 flex items-start gap-2 text-sm text-[#1E40AF]">
        <Brain className="mt-0.5 h-4 w-4 shrink-0 text-[#0F766E]" />
        <p className="whitespace-pre-wrap leading-relaxed">
          {content || ""}
          <span className="ml-1 inline-block h-4 w-1.5 animate-pulse bg-[#0F766E] align-middle" />
        </p>
      </div>
    </div>
  );
}
