/**
 * Java mirror for native C++ struct:
 *
 * <pre>
 * struct AsyncVLMResult {
 *   std::string result;
 *   size_t start_request_seq_id;
 *   size_t request_count;
 *   VITStats vit_stats;
 * };
 * </pre>
 *
 * <p>Notes:
 * <ul>
 *   <li>{@code std::string} is mirrored as {@link String}.</li>
 *   <li>{@code size_t} is mirrored as {@code long} in Java.</li>
 *   <li>{@code vit_stats} is mirrored as a {@link VITStats} instance.</li>
 * </ul>
 */
public final class AsyncVLMResult {

  /** 推理结果字符串 */
  public String result = "";

  /** 起始请求序号 */
  public long startRequestSeqId = 0L;

  /** 请求数量 */
  public long requestCount = 0L;

  /** VIT 任务统计信息 */
  public VITStats vitStats = new VITStats();

  /** Creates a result object with default values. */
  public AsyncVLMResult() {}

  public AsyncVLMResult(String result, long startRequestSeqId, long requestCount, VITStats vitStats) {
    this.result = (result == null) ? "" : result;
    this.startRequestSeqId = startRequestSeqId;
    this.requestCount = requestCount;
    this.vitStats = (vitStats == null) ? new VITStats() : new VITStats(vitStats);
  }

  public AsyncVLMResult(AsyncVLMResult other) {
    if (other == null) {
      throw new NullPointerException("other == null");
    }
    this.result = other.result;
    this.startRequestSeqId = other.startRequestSeqId;
    this.requestCount = other.requestCount;
    this.vitStats = (other.vitStats == null) ? new VITStats() : new VITStats(other.vitStats);
  }

  @Override
  public String toString() {
    return "AsyncVLMResult{"
        + "result='"
        + result
        + '\''
        + ", startRequestSeqId="
        + startRequestSeqId
        + ", requestCount="
        + requestCount
        + ", vitStats="
        + vitStats
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof AsyncVLMResult)) return false;
    AsyncVLMResult that = (AsyncVLMResult) o;

    if (startRequestSeqId != that.startRequestSeqId) return false;
    if (requestCount != that.requestCount) return false;
    if (result != null ? !result.equals(that.result) : that.result != null) return false;
    return vitStats != null ? vitStats.equals(that.vitStats) : that.vitStats == null;
  }

  @Override
  public int hashCode() {
    int resultHash = (result != null) ? result.hashCode() : 0;
    resultHash = 31 * resultHash + (int) (startRequestSeqId ^ (startRequestSeqId >>> 32));
    resultHash = 31 * resultHash + (int) (requestCount ^ (requestCount >>> 32));
    resultHash = 31 * resultHash + ((vitStats != null) ? vitStats.hashCode() : 0);
    return resultHash;
  }
}
