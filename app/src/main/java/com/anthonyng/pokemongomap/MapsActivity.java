package com.anthonyng.pokemongomap;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.util.Log;

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
public class MapsActivity extends FragmentActivity implements OnMapReadyCallback,
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
                if(selectedMarker != null) {
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
        String resourceName = "p" + catchablePokemon.getPokemonId().getNumber();
        int drawableResourceId = getResources().getIdentifier(resourceName, "drawable", getPackageName());

        // Place the marker on the map
        Marker pokemonMarker = googleMap.addMarker(new MarkerOptions()
                .position(location)
                .title(catchablePokemon.getPokemonId().name())
                .icon(BitmapDescriptorFactory.fromResource(drawableResourceId)));

        // Set expiration time
        if(catchablePokemon.getExpirationTimestampMs() != -1) {
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
                try {
                    // Log into Pokemon Go
                    OkHttpClient okHttpClient = new OkHttpClient();
                    PokemonGo pokemonGo = new PokemonGo(new PtcCredentialProvider(okHttpClient, "pokemongoapitest", "pokemongo"), okHttpClient);

                    // Set location
                    pokemonGo.setLocation(location.getLatitude(), location.getLongitude(), 0);

                    // Get nearby pokemon
                    subscriber.onNext(pokemonGo.getMap().getCatchablePokemon());
                    subscriber.onCompleted();
                } catch (LoginFailedException e) {

                } catch (RemoteServerException e) {

                }
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

                    }

                    @Override
                    public void onNext(List<CatchablePokemon> pokemonList) {
                        for (CatchablePokemon catchablePokemon : pokemonList) {

                            // Check if the pokemon is not already on the map
                            if (!pokemonSet.contains(catchablePokemon.getSpawnPointId())) {
                                placePokemonOnMap(catchablePokemon);
                                pokemonSet.add(catchablePokemon.getSpawnPointId());
                            }
                        }
                    }
                });

    }

    //endregion
}
