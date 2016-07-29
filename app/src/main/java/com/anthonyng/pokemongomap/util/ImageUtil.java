package com.anthonyng.pokemongomap.util;

import android.content.Context;

/**
 * Created by Anthony on 16-07-28.
 */
public class ImageUtil {

    public static int getPokemonDrawableResourceId(Context context, int number) {
        // Get the image associated with the pokemon
        String resourceName = "p" + number;
        int drawableResourceId = context.getResources().getIdentifier(
                resourceName, "drawable", context.getPackageName());

        return drawableResourceId;
    }
}
