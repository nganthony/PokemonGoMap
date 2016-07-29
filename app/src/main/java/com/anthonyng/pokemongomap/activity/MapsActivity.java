package com.anthonyng.pokemongomap.activity;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.anthonyng.pokemongomap.R;
import com.anthonyng.pokemongomap.preference.AppPreferences;
import com.anthonyng.pokemongomap.util.ImageUtil;
import com.anthonyng.pokemongomap.util.LocationUtil;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.pokegoapi.api.PokemonGo;
import com.pokegoapi.api.map.pokemon.CatchablePokemon;
import com.pokegoapi.auth.PtcCredentialProvider;
import com.pokegoapi.exceptions.LoginFailedException;
import com.pokegoapi.exceptions.RemoteServerException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import rx.Observable;
import rx.Subscriber;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

/**
 * Displays a map showing all locations of pokemon in the area
 */
public class MapsActivity extends AppCompatActivity implements OnMapReadyCallback,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener,
        LocationListener {

    private static final String TAG = "MapsActivity";
    private static final int REQUEST_ACCESS_FINE_LOCATION = 1;

    private GoogleMap googleMap;

    private GoogleApiClient googleApiClient;
    private LocationRequest locationRequest;
    private Marker selectedMarker;
    private boolean zoomedIntoCurrentLocation = false;

    private HashSet<String> pokemonSet = new HashSet<>();


    //region Lifecycle and Activity methods

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        // Request to access user's location
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_ACCESS_FINE_LOCATION);
        } else {
            // User already granted access, request for location updates
            initializeLocationServices();
        }
    }

    // this works
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.maps, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_filter:
                startActivity(new Intent(this, FilterPokemonActivity.class));
                return true;

            default:
                // If we got here, the user's action was not recognized.
                // Invoke the superclass to handle it.
                return super.onOptionsItemSelected(item);

        }
    }

    @Override
    protected void onDestroy() {
        googleApiClient.disconnect();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case REQUEST_ACCESS_FINE_LOCATION: {
                // If request is cancelled, the result arrays are empty
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    // Request for location updates
                    initializeLocationServices();
                }

                return;
            }
        }
    }

    //endregion

    //region Google Maps methods

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera.
     */
    @Override
    public void onMapReady(final GoogleMap googleMap) {
        this.googleMap = googleMap;
        this.googleMap.setMyLocationEnabled(true);
        this.googleMap.setOnMapClickListener(new GoogleMap.OnMapClickListener() {
            @Override
            public void onMapClick(LatLng latLng) {
                if (selectedMarker != null) {
                    selectedMarker.remove();
                }

                // Place user selected marker on the map
                selectedMarker = googleMap.addMarker(new MarkerOptions()
                        .position(latLng));

                // Retrieve pokemon within the area
                Location location = new Location(LocationManager.GPS_PROVIDER);
                location.setLatitude(latLng.latitude);
                location.setLongitude(latLng.longitude);
                requestPokemonInLocation(location);
            }
        });
    }

    /**
     * Places a marker according associated with the pokemon
     *
     * @param catchablePokemon
     */
    private void placePokemonOnMap(CatchablePokemon catchablePokemon) {
        LatLng location = new LatLng(catchablePokemon.getLatitude(), catchablePokemon.getLongitude());

        // Get the image associated with the pokemon
        int drawableResourceId = ImageUtil.getPokemonDrawableResourceId(
                getApplicationContext(), catchablePokemon.getPokemonId().getNumber());

        // Place the marker on the map
        Marker pokemonMarker = googleMap.addMarker(new MarkerOptions()
                .position(location)
                .title(catchablePokemon.getPokemonId().name())
                .icon(BitmapDescriptorFactory.fromResource(drawableResourceId)));

        // Set expiration time
        if (catchablePokemon.getExpirationTimestampMs() != -1) {
            long remainingDuration = catchablePokemon.getExpirationTimestampMs() - System.currentTimeMillis();
            String expirationTime = String.format("%d min, %d sec",
                    TimeUnit.MILLISECONDS.toMinutes(remainingDuration),
                    TimeUnit.MILLISECONDS.toSeconds(remainingDuration) -
                            TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(remainingDuration))
            );

            pokemonMarker.setSnippet(expirationTime);
        }
    }

    //endregion

    //region Google Play Services Methods

    /**
     * Connects to location services and creates a location request
     */
    private void initializeLocationServices() {
        locationRequest = LocationRequest.create();
        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        locationRequest.setInterval(10000);
        locationRequest.setFastestInterval(5000);

        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

        googleApiClient.connect();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            if (!zoomedIntoCurrentLocation) {
                LatLng loc = new LatLng(location.getLatitude(), location.getLongitude());
                if (googleMap != null) {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 16.0f));
                }

                selectedMarker = googleMap.addMarker(new MarkerOptions()
                        .position(loc));

                zoomedIntoCurrentLocation = true;
                requestPokemonInLocation(location);
            }
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, this);
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        // Refer to the javadoc for ConnectionResult to see what error codes might be returned in
        // onConnectionFailed.
        Log.i(TAG, "Connection failed: ConnectionResult.getErrorCode() = " + result.getErrorCode());
    }


    @Override
    public void onConnectionSuspended(int cause) {
        // The connection to Google Play services was lost for some reason. We call connect() to
        // attempt to re-establish the connection.
        Log.i(TAG, "Connection suspended");
        googleApiClient.connect();
    }

    //endregion

    //region Network Requests

    /**
     * Request pokemon in the area
     *
     * @param location Location to retrieve pokemon in area
     */
    private void requestPokemonInLocation(final Location location) {

        Observable<List<CatchablePokemon>> catchablePokemonObservable = Observable.create(new Observable.OnSubscribe<List<CatchablePokemon>>() {

            @Override
            public void call(Subscriber<? super List<CatchablePokemon>> subscriber) {
                List<LatLng> scanMap = new ArrayList<LatLng>();
                makeHexScanMap(new LatLng(location.getLatitude(), location.getLongitude()), 12, 1, scanMap);
                List<CatchablePokemon> catchablePokemonList = new ArrayList<>();

                try {
                    // Log into Pokemon Go
                    OkHttpClient okHttpClient = new OkHttpClient();
                    PokemonGo pokemonGo = new PokemonGo(new PtcCredentialProvider(okHttpClient, "pokemongoapitest", "pokemongo"), okHttpClient);

                    for (LatLng latLng : scanMap) {
                        // Set location
                        pokemonGo.setLocation(latLng.latitude, latLng.longitude, 0);

                        // Fetch pokemon in location
                        catchablePokemonList.addAll(pokemonGo.getMap().getCatchablePokemon());
                    }

                } catch (LoginFailedException e) {
                    subscriber.onError(e);
                } catch (RemoteServerException e) {
                    subscriber.onError(e);
                }

                subscriber.onNext(catchablePokemonList);
                subscriber.onCompleted();
            }
        });

        catchablePokemonObservable.subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Subscriber<List<CatchablePokemon>>() {
                    @Override
                    public void onCompleted() {

                    }

                    @Override
                    public void onError(Throwable e) {
                        Snackbar.make(findViewById(android.R.id.content),
                                getString(R.string.snack_bar_pokemon_trainer_club_error),
                                Snackbar.LENGTH_INDEFINITE)
                                .setAction(getString(R.string.snack_bar_retry), new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        requestPokemonInLocation(LocationUtil.latLngToLocation(selectedMarker.getPosition()));
                                    }
                                }).show();
                    }

                    @Override
                    public void onNext(List<CatchablePokemon> pokemonList) {
                        for (CatchablePokemon catchablePokemon : pokemonList) {
                            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

                            // Check if user has filter
                            boolean showPokemon = preferences.getBoolean(AppPreferences.PREFERENCE_KEY_SHOW_POKEMON
                                    + catchablePokemon.getPokemonId().getNumber(), true);

                            if (showPokemon) {
                                // Check if the pokemon is not already on the map
                                if (!pokemonSet.contains(catchablePokemon.getSpawnPointId())) {
                                    placePokemonOnMap(catchablePokemon);
                                    pokemonSet.add(catchablePokemon.getSpawnPointId());
                                }
                            }
                        }
                    }
                });

    }

    private List<LatLng> makeHexScanMap(LatLng loc, int steps, int layer_count, List<LatLng> scanMap) {
        // Base case is do nothing
        if (steps > 0) {
            if (layer_count == 1) {
                // Add in the point, no translation since 1st layer
                scanMap.add(loc);
            } else {
                double distance = 200; // in meters
                // add a point that is distance due north
                scanMap.add(translate(loc, 0.0, distance));
                // go south-east
                for (int i = 0; i < layer_count - 1; i++) {
                    LatLng prev = scanMap.get(scanMap.size() - 1);
                    LatLng next = translate(prev, 120.0, distance);
                    scanMap.add(next);
                }
                // go due south
                for (int i = 0; i < layer_count - 1; i++) {
                    LatLng prev = scanMap.get(scanMap.size() - 1);
                    LatLng next = translate(prev, 180.0, distance);
                    scanMap.add(next);
                }
                // go south-west
                for (int i = 0; i < layer_count - 1; i++) {
                    LatLng prev = scanMap.get(scanMap.size() - 1);
                    LatLng next = translate(prev, 240.0, distance);
                    scanMap.add(next);
                }
                // go north-west
                for (int i = 0; i < layer_count - 1; i++) {
                    LatLng prev = scanMap.get(scanMap.size() - 1);
                    LatLng next = translate(prev, 300.0, distance);
                    scanMap.add(next);
                }
                // go due north
                for (int i = 0; i < layer_count - 1; i++) {
                    LatLng prev = scanMap.get(scanMap.size() - 1);
                    LatLng next = translate(prev, 0.0, distance);
                    scanMap.add(next);
                }
                // go north-east
                for (int i = 0; i < layer_count - 2; i++) {
                    LatLng prev = scanMap.get(scanMap.size() - 1);
                    LatLng next = translate(prev, 60.0, distance);
                    scanMap.add(next);
                }
            }
            return makeHexScanMap(scanMap.get(hexagonal_number(layer_count - 1)), steps - 1, layer_count + 1, scanMap);
        } else {
            return scanMap;
        }
    }

    private LatLng translate(LatLng cur, double bearing, double distance) {
        double earth = 6378.1; // Radius of Earth in km
        double rad_bear = Math.toRadians(bearing);
        double dist_km = distance / 1000;
        double lat1 = Math.toRadians(cur.latitude);
        double lon1 = Math.toRadians(cur.longitude);
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(dist_km / earth) +
                Math.cos(lat1) * Math.sin(dist_km / earth) * Math.cos(rad_bear));
        double lon2 = lon1 + Math.atan2(Math.sin(rad_bear) * Math.sin(dist_km / earth) * Math.cos(lat1),
                Math.cos(dist_km / earth) - Math.sin(lat1) * Math.sin(lat2));
        lat2 = Math.toDegrees(lat2);
        lon2 = Math.toDegrees(lon2);
        return new LatLng(lat2, lon2);
    }

    public int hexagonal_number(int n) {
        return (n == 0) ? 0 : 3 * n * (n - 1) + 1;
    }

    //endregion
}
