package com.supermarket.util;

import java.util.List;
import java.util.function.Predicate;

public class FilterUtil<T> {

    public List<T> filter(List<T> items, Predicate<T> predicate) {
        return items.stream()
                .filter(predicate)
                .toList();
    }
}
