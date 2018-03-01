package org.point85.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;

public class DomainUtils {

	public static String[] parseDomainAndUser(String user) {

		if (user == null) {
			return null;
		}

		String delimeters = "[/" + "\\Q\\\\E" + "]+";
		String[] info = new String[2];
		String[] tokens = user.split(delimeters);

		info[0] = "localhost";
		info[1] = user;

		if (tokens.length == 2) {
			info[0] = tokens[0];
			info[1] = tokens[1];
		}

		return info;
	}

	public static String zonedDateTimeToString(ZonedDateTime zdt) {
		return zdt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}

	public static String offsetDateTimeToString(OffsetDateTime odt) {
		return odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}

	public static OffsetDateTime offsetDateTimeFromString(String timestamp) throws Exception {
		if (timestamp == null) {
			return OffsetDateTime.now();
		}

		// look for T separator
		if (timestamp.indexOf('T') == -1) {
			// missing T
			int space = timestamp.indexOf(' ');

			if (space != -1) {
				timestamp.split(" ");
			}
		}
		return OffsetDateTime.parse(timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
	}
	
	// create a UTC ZonedDateTime from the DateTime
	public static synchronized ZonedDateTime utcTimeFromDateTime(DateTime dateTime) {
		long epochMillis = dateTime.getJavaTime();
		Instant instant = Instant.ofEpochMilli(epochMillis);
		ZonedDateTime time = ZonedDateTime.ofInstant(instant, ZoneId.of("Z"));
		return time;
	}

	// create a local ZonedDateTime from the DateTime
	public static synchronized OffsetDateTime localTimeFromDateTime(DateTime dateTime) {
		ZonedDateTime utc = utcTimeFromDateTime(dateTime);
		ZonedDateTime time = utc.withZoneSameInstant(ZoneId.systemDefault());
		return OffsetDateTime.from(time);
	}
	
	public static OffsetDateTime fromLocalDateTime(LocalDateTime ldt) {
		ZoneOffset offset = OffsetDateTime.now().getOffset();
		return OffsetDateTime.of(ldt, offset);
	}
}