package com.viris.PulseGuard.stats;

import com.viris.PulseGuard.enumeration.StatsRange;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * Lets {@code ?range=24h} bind to {@link StatsRange}. Boot registers Converter beans with
 * Spring MVC; a failure surfaces as MethodArgumentTypeMismatchException, which the global
 * handler turns into a 400 listing the valid labels.
 */
@Component
public class StatsRangeConverter implements Converter<String, StatsRange> {

    @Override
    public StatsRange convert(String source) {
        return StatsRange.fromLabel(source);
    }
}
