package de.tum.in.tumcampusapp.widgets;

import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Switch;

import com.google.common.base.Optional;

import de.tum.in.tumcampusapp.R;
import de.tum.in.tumcampusapp.activities.TransportationActivity;
import de.tum.in.tumcampusapp.activities.generic.ActivityForSearchingInBackground;
import de.tum.in.tumcampusapp.adapters.NoResultsAdapter;
import de.tum.in.tumcampusapp.auxiliary.MVVStationSuggestionProvider;
import de.tum.in.tumcampusapp.managers.RecentsManager;
import de.tum.in.tumcampusapp.managers.TransportManager;
import de.tum.in.tumcampusapp.models.efa.StationResult;
import de.tum.in.tumcampusapp.models.efa.WidgetDepartures;

public class MVVWidgetConfigureActivity extends ActivityForSearchingInBackground<Cursor> implements AdapterView.OnItemClickListener {

    private int appWidgetId;
    private ListView listViewResults;
    private ArrayAdapter<StationResult> adapterStations;
    private RecentsManager recentsManager;

    private WidgetDepartures widgetDepartures;

    public MVVWidgetConfigureActivity() {
        super(R.layout.activity_mvv_widget_configure, MVVStationSuggestionProvider.AUTHORITY, 3);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Setup cancel button
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeAsUpIndicator(R.drawable.ic_action_cancel);

        // Get appWidgetId from intent
        Intent intent = getIntent();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        TransportManager tm = new TransportManager(this);
        this.widgetDepartures = tm.getWidget(appWidgetId);

        Switch autoReloadSwitch = findViewById(R.id.mvv_widget_auto_reload);
        autoReloadSwitch.setChecked(this.widgetDepartures.getAutoReload());
        autoReloadSwitch.setOnCheckedChangeListener((compoundButton, checked) -> widgetDepartures.setAutoReload(checked));
        // TODO add handling for use location

        // Det all stations from db
        recentsManager = new RecentsManager(this, RecentsManager.STATIONS);

        listViewResults = findViewById(R.id.activity_transport_listview_result);
        listViewResults.setOnItemClickListener(this);

        // Initialize stations adapter
        try (Cursor stationCursor = recentsManager.getAllFromDb()) {
            adapterStations = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, TransportationActivity.getAllStationResults(stationCursor));
        }

        if (adapterStations.getCount() == 0) {
            openSearch();
        } else {
            listViewResults.setAdapter(adapterStations);
            listViewResults.requestFocus();
        }
    }

    /**
     * Click on station in list
     */
    @Override
    public void onItemClick(final AdapterView<?> av, View v, int position, long id) {
        StationResult stationResult = (StationResult) av.getAdapter()
                                                        .getItem(position);
        widgetDepartures.setStation(stationResult.getStation());
        widgetDepartures.setStationId(stationResult.getId());
        saveAndReturn();
    }

    /**
     * Shows all recently used stations
     *
     * @return Cursor holding the recents information (name, _id)
     */
    @Override
    public Optional<Cursor> onSearchInBackground() {
        return Optional.of(recentsManager.getAllFromDb());
    }

    /**
     * Searches the Webservice for stations
     *
     * @param query the text entered by the user
     * @return Cursor holding the stations (name, _id)
     */
    @Override
    public Optional<Cursor> onSearchInBackground(String query) {
        // Get Information
        Optional<Cursor> stationCursor = TransportManager.getStationsFromExternal(this, query);
        if (!stationCursor.isPresent()) {
            showError(R.string.exception_unknown);
        }

        // Drop results if canceled
        if (asyncTask.isCancelled()) {
            return Optional.absent();
        }

        return stationCursor;
    }

    /**
     * Shows the stations
     *
     * @param possibleStationCursor Cursor with stations (name, _id)
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Override
    protected void onSearchFinished(Optional<Cursor> possibleStationCursor) {
        if (!possibleStationCursor.isPresent()) {
            return;
        }
        try (Cursor stationCursor = possibleStationCursor.get()) {
            showLoadingEnded();

            // mQuery is not null if it was a real search
            if (stationCursor.getCount() == 0) {
                // When stationCursor is a MatrixCursor the result comes from querying a station name
                if (stationCursor instanceof MatrixCursor) {
                    // So show no results found
                    listViewResults.setAdapter(new NoResultsAdapter(this));
                    listViewResults.requestFocus();
                } else {
                    // if the loading came from the user canceling search
                    // and there are no recents to show close activity
                    cancelAndReturn();
                }
                return;
            }

            adapterStations.clear();
            adapterStations.addAll(TransportationActivity.getAllStationResults(stationCursor));
        }
        adapterStations.notifyDataSetChanged();
        listViewResults.setAdapter(adapterStations);
        listViewResults.requestFocus();
    }

    /**
     * Setup cancel and back action
     *
     * @param item the menu item which has been pressed (or activated)
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                cancelAndReturn();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Saves the selection to the database, triggers a widget update and closes this activity
     */
    private void saveAndReturn() {
        // save the settings
        TransportManager transportManager = new TransportManager(this);
        transportManager.addWidget(appWidgetId, this.widgetDepartures);

        // update alarms
        MVVWidget.setAlarm(this);

        // update widget
        Intent reloadIntent = new Intent(this, MVVWidget.class);
        reloadIntent.setAction(MVVWidget.MVV_WIDGET_FORCE_RELOAD);
        reloadIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        sendBroadcast(reloadIntent);

        // return to widget
        Intent resultValue = new Intent();
        resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, resultValue);
        finish();
    }

    /**
     * Cancel the widget creation and close this activity
     */
    private void cancelAndReturn() {
        Intent resultValue = new Intent();
        if (!widgetDepartures.getStation()
                             .isEmpty() && !widgetDepartures.getStationId()
                                                            .isEmpty()) {
            saveAndReturn();
        } else {
            resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            setResult(RESULT_CANCELED, resultValue);
            finish();
        }
    }
}
