package com.example.runtime.core;

import java.nio.file.*;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeConfigurationCheck implements SmartInitializingSingleton {
    private final Environment env;
    public RuntimeConfigurationCheck(Environment env) {this.env=env;}
    public void afterSingletonsInstantiated() {
        if(env.matchesProfiles("prod") && env.matchesProfiles("local"))
            throw new IllegalStateException("The local and prod profiles cannot be enabled together");
        try {Files.createDirectories(Path.of(".runtime","harness"));}
        catch(java.io.IOException e) {throw new IllegalStateException("Cannot create runtime workspace");}
    }
}
