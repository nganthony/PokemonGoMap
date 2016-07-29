package com.anthonyng.pokemongomap.fragment;


import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;

import com.anthonyng.pokemongomap.R;
import com.anthonyng.pokemongomap.preference.AppPreferences;
import com.anthonyng.pokemongomap.util.FileUtil;
import com.anthonyng.pokemongomap.util.ImageUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;

/**
 * Contains a list of pokemon that can be filtered from the map
 */
public class FilterPokemonFragment extends PreferenceFragment {

    public FilterPokemonFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PreferenceScreen preferenceScreen = getPreferenceManager()
                .createPreferenceScreen(getActivity().getApplicationContext());

        // Add a category
        PreferenceCategory filterCategory = new PreferenceCategory(getActivity().getApplicationContext());
        filterCategory.setTitle(getString(R.string.preference_category_filter_pokemon_title));
        preferenceScreen.addPreference(filterCategory);

        try {
            // Read the pokemon.json file to obtain the list of pokemon
            InputStream inputStream = getActivity().getApplicationContext().getAssets().open("pokemon.json");
            String jsonPokemonString = FileUtil.inputStreamToString(inputStream);
            JSONArray pokemonJsonArray = new JSONArray(jsonPokemonString);

            // Go through each pokemon and add a checkbox preference
            for(int i = 0; i < pokemonJsonArray.length(); i++){
                JSONObject pokemonJsonObject = pokemonJsonArray.getJSONObject(i);

                int pokemonNumber = Integer.parseInt(pokemonJsonObject.getString("Number"));

                CheckBoxPreference checkBox = new CheckBoxPreference(getActivity().getApplicationContext());
                checkBox.setTitle(pokemonJsonObject.getString("Name"));
                checkBox.setIcon(ImageUtil.getPokemonDrawableResourceId(
                        getActivity().getApplicationContext(), pokemonNumber));
                checkBox.setDefaultValue(true);
                checkBox.setKey(AppPreferences.PREFERENCE_KEY_SHOW_POKEMON + pokemonNumber);

                filterCategory.addPreference(checkBox);
            }
        } catch (IOException e) {
            //
        } catch (JSONException e) {

        }

        setPreferenceScreen(preferenceScreen);
    }
}
