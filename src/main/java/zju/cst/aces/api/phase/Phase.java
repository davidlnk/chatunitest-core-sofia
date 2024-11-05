package zju.cst.aces.api.phase;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.phase.step.*;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;

public class Phase {
    public enum PhaseType {
        TEPLA,
        TEST_PILOT,
        COVER_UP,
        HITS
    }

    protected final Config config;

    public Phase(Config config) {
        this.config = config;
    }

    public void prepare() {
        new Preparation(config).execute();
    }

    public PromptConstructorImpl generatePrompt(ClassInfo classInfo, MethodInfo methodInfo, int num) {
        return new PromptGeneration(config, classInfo, methodInfo).execute(num);
    }

    public void generateTest(PromptConstructorImpl pc) {
        new TestGeneration(config).execute(pc);
    }

    public boolean validateTest(PromptConstructorImpl pc) {
        return new Validation(config).execute(pc);
    }

    public void repairTest(PromptConstructorImpl pc) {
        new Repair(config).execute(pc);
    }

    // Factory method to select the appropriate Phase subclass based on config
    public Phase createPhase() {
        // Example logic to select Phase subclass based on config properties
        String phaseTypeString = config.getPhaseType();
        PhaseType phaseType;

        try {
            phaseType = PhaseType.valueOf(phaseTypeString); // todo 这里似乎如果没有找到枚举对象会直接崩溃
        } catch (IllegalArgumentException e) {
            return new Phase(config); // Default or fallback Phase
        }

        switch (phaseType) {
            case TEPLA:
                return new Phase_TEPLA(config);
            case TEST_PILOT:
                return new Phase_TestPilot(config);
            case COVER_UP:
                return new Phase_CoverUp(config);
            case HITS:
                return new Phase_HITS(config);
            default:
                return new Phase(config); // Default or fallback Phase
        }
    }
}