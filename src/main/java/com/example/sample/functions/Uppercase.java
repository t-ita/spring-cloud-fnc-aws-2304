package com.example.sample.functions;

import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Function;

public class Uppercase implements Function<String, String>, Resource {

    private static final Logger logger = LoggerFactory.getLogger(Uppercase.class);

    public Uppercase() {
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        logger.info("Before checkpoint");
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
        logger.info("After restore");
    }

    @Override
    public String apply(String s) {
        return s.toUpperCase();
    }
}
