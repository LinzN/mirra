package de.linzn.mirra.core.functions;

import com.theokanning.openai.completion.chat.ChatFunctionDynamic;
import com.theokanning.openai.completion.chat.ChatFunctionProperty;
import de.linzn.mirra.identitySystem.AiPermissions;
import de.linzn.mirra.identitySystem.IdentityUser;
import de.linzn.mirra.identitySystem.UserToken;
import de.linzn.weather.WeatherPlugin;
import de.linzn.weather.engine.WeatherContainer;
import de.linzn.weather.engine.WeatherEngine;
import org.json.JSONObject;

public class Weather implements IFunction {
    @Override
    public JSONObject completeRequest(JSONObject input, IdentityUser identityUser, UserToken userToken) {
        JSONObject jsonObject = new JSONObject();
        if (identityUser.hasPermission(AiPermissions.GET_WEATHER)) {
            jsonObject.put("success", true);
            String key = WeatherPlugin.weatherPlugin.getDefaultConfig().getString("weather.apiKey");
            WeatherContainer weatherContainer = new WeatherEngine(key).getCurrentWeather(input.getString("location"));

            jsonObject.put("success", true);
            jsonObject.put("location", weatherContainer.getLocation());
            jsonObject.put("humidity", weatherContainer.getHumidity());
            jsonObject.put("temperature", weatherContainer.getTemp());
            jsonObject.put("temperature_max", weatherContainer.getTemp_max());
            jsonObject.put("temperature_min", weatherContainer.getTemp_min());
            jsonObject.put("pressure", weatherContainer.getPressure());
            jsonObject.put("weather", weatherContainer.getWeatherMain());
            jsonObject.put("weatherDescription", weatherContainer.getWeatherDescription());
        } else {
            jsonObject.put("success", false);
            jsonObject.put("reason", "No permissions");
        }
        return jsonObject;
    }

    @Override
    public ChatFunctionDynamic getFunctionString() {
        return ChatFunctionDynamic.builder()
                .name(this.functionName())
                .description("Get the weather data for a location")
                .addProperty(ChatFunctionProperty.builder()
                        .name("location")
                        .type("string")
                        .description("The location to get the weather data! Like 'Blieskastel'")
                        .required(true)
                        .build())
                .build();
    }

    @Override
    public String functionName() {
        return "get_weather";
    }

}
