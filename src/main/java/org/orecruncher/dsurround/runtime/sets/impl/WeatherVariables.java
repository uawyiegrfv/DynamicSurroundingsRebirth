package org.orecruncher.dsurround.runtime.sets.impl;

import org.orecruncher.dsurround.lib.GameUtils;
import org.orecruncher.dsurround.lib.di.ContainerManager;
import org.orecruncher.dsurround.lib.scripting.IVariableAccess;
import org.orecruncher.dsurround.lib.scripting.VariableSet;
import org.orecruncher.dsurround.lib.seasons.ISeasonalInformation;
import org.orecruncher.dsurround.processing.PrecipitationIntensity;
import org.orecruncher.dsurround.runtime.sets.IWeatherVariables;
import org.orecruncher.dsurround.processing.weather.WindField;

public class WeatherVariables extends VariableSet<IWeatherVariables> implements IWeatherVariables {

    private final ISeasonalInformation seasonalInformation;

    private float temperature;
    private boolean isRaining;
    private boolean isThundering;
    private float rainIntensity;
    private float thunderIntensity;
    // Our graded values - see IWeatherVariables for why there are two.
    private float gradedIntensity;
    private float gradedPrecipitation;
    private boolean isFrosty;
    private boolean canWaterFreeze;
    private float windDirection;
    private float windSpeed;


    public WeatherVariables(ISeasonalInformation seasonalInformation) {
        super("weather");
        this.seasonalInformation = seasonalInformation;
    }

    @Override
    public IWeatherVariables getInterface() {
        return this;
    }

    @Override
    public void update(IVariableAccess variableAccess) {
        if (GameUtils.isInGame()) {
            final var player = GameUtils.getPlayer().orElseThrow();
            final var world = player.level();
            this.rainIntensity = world.getRainLevel(1F);
            this.thunderIntensity = world.getThunderLevel(1F);
            // Graded values fall back to the vanilla ramp when grading is switched
            // off, so that a condition written against them keeps meaning "how hard
            // is it raining" instead of silently going to zero along with the
            // feature that introduced them.
            final boolean grading = PrecipitationIntensity.grading();
            this.gradedIntensity = grading ? PrecipitationIntensity.ambient() : this.rainIntensity;
            this.gradedPrecipitation = grading ? PrecipitationIntensity.intensity() : this.rainIntensity;
            this.isRaining = world.isRaining();
            this.isThundering = world.isThundering();
            this.temperature = this.seasonalInformation.getTemperature(player.blockPosition());
            this.isFrosty = this.seasonalInformation.isColdTemperature(player.blockPosition());
            this.canWaterFreeze = this.seasonalInformation.isSnowTemperature(player.blockPosition());
            // WIND：风场是游戏时钟的纯函数，这里没有绘制通道，直接用无缓存版本。
            this.windDirection = WindField.angleAt(world.getGameTime(), 0F);
            this.windSpeed = WindField.speedAt(this.gradedIntensity, PrecipitationIntensity.gustAmplitude());
        } else {
            this.rainIntensity = 0F;
            this.thunderIntensity = 0F;
            this.gradedIntensity = 0F;
            this.gradedPrecipitation = 0F;
            this.isRaining = false;
            this.isThundering = false;
            this.temperature = 0;
            this.isFrosty = false;
            this.canWaterFreeze = false;
            this.windDirection = 0F;
            this.windSpeed = 0F;
        }
    }

    @Override
    public boolean isRaining() {
        return this.isRaining;
    }

    @Override
    public boolean isThundering() {
        return this.isThundering;
    }

    @Override
    public float getRainIntensity() {
        return this.rainIntensity;
    }

    @Override
    public float getThunderIntensity() {
        return this.thunderIntensity;
    }

    @Override
    public float getGradedIntensity() {
        return this.gradedIntensity;
    }

    @Override
    public float getGradedPrecipitation() {
        return this.gradedPrecipitation;
    }

    @Override
    public float getTemperature() {
        return this.temperature;
    }

    @Override
    public boolean isFrosty() {
        return this.isFrosty;
    }

    @Override
    public boolean canWaterFreeze() {
        return this.canWaterFreeze;
    }

    @Override
    public float getWindDirection() {
        return this.windDirection;
    }

    @Override
    public float getWindSpeed() {
        return this.windSpeed;
    }
}