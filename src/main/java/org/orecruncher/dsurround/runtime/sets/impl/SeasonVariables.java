package org.orecruncher.dsurround.runtime.sets.impl;

import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.scripting.IVariableAccess;
import org.orecruncher.dsurround.lib.scripting.VariableSet;
import org.orecruncher.dsurround.lib.seasons.ISeasonalInformation;
import org.orecruncher.dsurround.lib.seasons.SeasonPhase;
import org.orecruncher.dsurround.runtime.sets.ISeasonVariables;

public class SeasonVariables extends VariableSet<ISeasonVariables> implements ISeasonVariables {

    private final ISeasonalInformation seasonalInformation;

    private boolean isSpring;
    private boolean isSummer;
    private boolean isAutumn;
    private boolean isWinter;

    private boolean isEarly;
    private boolean isMiddle;
    private boolean isLate;

    public SeasonVariables(ISeasonalInformation seasonalInformation) {
        super("season");
        this.seasonalInformation = seasonalInformation;
    }

    @Override
    public ISeasonVariables getInterface() {
        return this;
    }

    @Override
    public void update(IVariableAccess variableAccess) {
        if (GameUtils.isInGame()) {
            this.isSpring = this.seasonalInformation.isSpring();
            this.isSummer = this.seasonalInformation.isSummer();
            this.isAutumn = this.seasonalInformation.isAutumn();
            this.isWinter = this.seasonalInformation.isWinter();
        } else {
            this.isSpring = false;
            this.isSummer = false;
            this.isAutumn = false;
            this.isWinter = false;
        }

        // Sub-season tests. Read hasSeasonalCycle() rather than getSeasonPhase() alone:
        // a provider without sub-seasons reports MID, which would make isEarly/isMiddle/
        // isLate evaluate false/false/true and silently disable any pack script using them.
        if (GameUtils.isInGame() && this.seasonalInformation.hasSeasonalCycle()) {
            final var phase = this.seasonalInformation.getSeasonPhase();
            this.isEarly = phase == SeasonPhase.EARLY;
            this.isMiddle = phase == SeasonPhase.MID;
            this.isLate = phase == SeasonPhase.LATE;
        } else {
            this.isEarly = true;
            this.isMiddle = true;
            this.isLate = true;
        }
    }

    @Override
    public boolean isSpring() {
        return this.isSpring;
    }

    @Override
    public boolean isSummer() {
        return this.isSummer;
    }

    @Override
    public boolean isAutumn() {
        return this.isAutumn;
    }

    @Override
    public boolean isWinter() {
        return this.isWinter;
    }

    @Override
    public boolean isEarly() {
        return this.isEarly;
    }

    @Override
    public boolean isMiddle() {
        return this.isMiddle;
    }

    @Override
    public boolean isLate() {
        return this.isLate;
    }
}
