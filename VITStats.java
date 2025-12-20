/**
 * Java mirror for native C struct:
 *
 * <pre>
 * struct VITStats {
 *   size_t success_count = 0;
 *   size_t discard_count = 0;
 *   double discard_rate = 0.0;
 *   size_t history_size = 0;
 * };
 * </pre>
 *
 * <p>Notes:
 * <ul>
 *   <li>{@code size_t} is mirrored as {@code long} in Java.</li>
 *   <li>All fields are initialized with the same default values as the native struct.</li>
 * </ul>
 */
public final class VITStats {

  /** 最近 N 次中成功的次数 */
  public long successCount = 0L;

  /** 最近 N 次中丢弃的次数 */
  public long discardCount = 0L;

  /** 最近 N 次的丢弃率 */
  public double discardRate = 0.0d;

  /** 历史记录数量（最多 N 次） */
  public long historySize = 0L;

  /** Creates a stats object with default values (all zeros). */
  public VITStats() {}

  public VITStats(long successCount, long discardCount, double discardRate, long historySize) {
    this.successCount = successCount;
    this.discardCount = discardCount;
    this.discardRate = discardRate;
    this.historySize = historySize;
  }

  public VITStats(VITStats other) {
    if (other == null) {
      throw new NullPointerException("other == null");
    }
    this.successCount = other.successCount;
    this.discardCount = other.discardCount;
    this.discardRate = other.discardRate;
    this.historySize = other.historySize;
  }

  @Override
  public String toString() {
    return "VITStats{"
        + "successCount="
        + successCount
        + ", discardCount="
        + discardCount
        + ", discardRate="
        + discardRate
        + ", historySize="
        + historySize
        + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof VITStats)) return false;
    VITStats that = (VITStats) o;
    return successCount == that.successCount
        && discardCount == that.discardCount
        && Double.doubleToLongBits(discardRate) == Double.doubleToLongBits(that.discardRate)
        && historySize == that.historySize;
  }

  @Override
  public int hashCode() {
    long bits = Double.doubleToLongBits(discardRate);
    int result = (int) (successCount ^ (successCount >>> 32));
    result = 31 * result + (int) (discardCount ^ (discardCount >>> 32));
    result = 31 * result + (int) (bits ^ (bits >>> 32));
    result = 31 * result + (int) (historySize ^ (historySize >>> 32));
    return result;
  }
}
