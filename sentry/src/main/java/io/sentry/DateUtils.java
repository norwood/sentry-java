package io.sentry;

import static io.sentry.vendor.gson.internal.bind.util.ISO8601Utils.TIMEZONE_UTC;

import io.sentry.vendor.gson.internal.bind.util.ISO8601Utils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.ParseException;
import java.text.ParsePosition;
import java.util.Calendar;
import java.util.Date;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/** Utilities to deal with dates */
@ApiStatus.Internal
public final class DateUtils {

  private DateUtils() {}

  /**
   * Get the current Date (UTC)
   *
   * @return the UTC Date
   */
  @SuppressWarnings("JdkObsolete")
  public static @NotNull Date getCurrentDateTime() {
    final Calendar calendar = Calendar.getInstance(TIMEZONE_UTC);
    return calendar.getTime();
  }

  /**
   * Get the Date from UTC/ISO 8601 timestamp
   *
   * @param timestamp UTC/ISO 8601 format eg 2000-12-31T23:59:58Z or 2000-12-31T23:59:58.123Z
   * @return the UTC Date
   */
  public static @NotNull Date getDateTime(final @NotNull String timestamp)
      throws IllegalArgumentException {
    try {
      return ISO8601Utils.parse(timestamp, new ParsePosition(0));
    } catch (ParseException e) {
      throw new IllegalArgumentException("timestamp is not ISO format " + timestamp);
    }
  }

  /**
   * Get the Date from millis timestamp
   *
   * @param timestamp millis eg 1581410911.988 (1581410911 seconds and 988 millis)
   * @return the UTC Date
   */
  @SuppressWarnings("JdkObsolete")
  public static @NotNull Date getDateTimeWithMillisPrecision(final @NotNull String timestamp)
      throws IllegalArgumentException {
    try {
      return getDateTime(
          new BigDecimal(timestamp).setScale(3, RoundingMode.DOWN).movePointRight(3).longValue());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("timestamp is not millis format " + timestamp);
    }
  }

  /**
   * Get the UTC/ISO 8601 timestamp from Date
   *
   * @param date the UTC Date
   * @return the UTC/ISO 8601 timestamp
   */
  public static @NotNull String getTimestamp(final @NotNull Date date) {
    return ISO8601Utils.format(date, true);
  }

  /**
   * Get the Date from millis timestamp
   *
   * @param millis the UTC millis from the epoch
   * @return the UTC Date
   */
  public static @NotNull Date getDateTime(final long millis) {
    final Calendar calendar = Calendar.getInstance(TIMEZONE_UTC);
    calendar.setTimeInMillis(millis);
    return calendar.getTime();
  }

  /**
   * Converts milliseconds to seconds.
   *
   * @param millis - milliseconds
   * @return seconds
   */
  public static double millisToSeconds(final double millis) {
    return millis / 1000;
  }

  /**
   * Converts nanoseconds to milliseconds
   *
   * @param nanos - nanoseconds
   * @return milliseconds
   */
  public static double nanosToMillis(final double nanos) {
    return nanos / 1000000;
  }

  /**
   * Convert {@link Date} to epoch time in seconds represented as {@link Double}.
   *
   * @param date - date
   * @return seconds
   */
  @SuppressWarnings("JavaUtilDate")
  public static double dateToSeconds(final @NotNull Date date) {
    return millisToSeconds(date.getTime());
  }
}
