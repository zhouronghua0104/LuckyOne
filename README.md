# CSV 按 `batch.log` 文件名顺序排序

本仓库提供脚本：将 `export_哨兵_第四批.csv` 按 `batch.log` 中定义的文件名顺序重新排序，并输出到新的 CSV 文件。

## 输入格式假设

- `export_哨兵_第四批.csv`
  - 第 1 行：Header（列名）
  - 第 2 行起：每行一个结果
  - 其中有一列是文件名，默认列名为：`文件名称（输入）`
- `batch.log`
  - 每行一个文件名（空行忽略；以 `#` 开头的行忽略）

## 使用方法

把 `export_哨兵_第四批.csv` 和 `batch.log` 放在脚本同目录，然后运行：

```bash
python3 sort_csv_by_batch_log.py
```

默认输出：`export_哨兵_第四批.sorted.csv`

也可以显式指定路径：

```bash
python3 sort_csv_by_batch_log.py \
  --input-csv "export_哨兵_第四批.csv" \
  --batch-log "batch.log" \
  --output-csv "export_哨兵_第四批.sorted.csv"
```

如果文件名列不是 `文件名称（输入）`，可指定列名：

```bash
python3 sort_csv_by_batch_log.py --filename-column "文件名称（输入）"
```

## 未匹配行处理

- 默认：`--unmatched keep_end`（不在 `batch.log` 的行，保持原相对顺序并追加到末尾）
- 丢弃未匹配行：`--unmatched drop`

