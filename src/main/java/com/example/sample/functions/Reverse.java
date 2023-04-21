package com.example.sample.functions;

import java.util.function.Function;

public class Reverse implements Function<String, String> {
    @Override
    public String apply(String s) {
        return new StringBuilder(s).reverse().toString();
    }
}
