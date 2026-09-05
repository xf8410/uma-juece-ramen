package com.umaai.assistant.service;

/**
 * 一条决策记录的 RAM 表示（内存双区 + 本机 HTTP API 的最小数据单元）。
 *
 * pending 上传队列（GitHubUploader.queue）与 recent 环形缓存持有同一个
 * RamenRecord 引用，两区不复制数据；持久化层（TrainingDataStore）按
 * jsonl 原文落盘，seq 与磁盘全局位置一一对应。
 *
 * - jsonl：原样 JSONL 行（字段一字不改，上传/落盘格式红线）
 * - seq：记录产生时分配的全局单调递增序号。由持久化层分配（seqBase +
 *   磁盘位置派生，进程重启后延续且与重启前一致），供本机 HTTP API 的
 *   GET /data?after=seq 增量拉取使用；recent 丢最旧不影响 after 增量语义
 */
final class RamenRecord {
    final String jsonl;
    final long seq;

    RamenRecord(String jsonl, long seq) {
        this.jsonl = jsonl;
        this.seq = seq;
    }
}
