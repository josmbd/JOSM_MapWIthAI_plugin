// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.mapwithai.backend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Future;

import org.awaitility.Awaitility;
import org.awaitility.Durations;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;
import org.openstreetmap.josm.TestUtils;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.Notification;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.mapwithai.backend.commands.conflation.ConnectedCommand;
import org.openstreetmap.josm.plugins.mapwithai.backend.commands.conflation.DuplicateCommand;
import org.openstreetmap.josm.plugins.mapwithai.testutils.MissingConnectionTagsMocker;
import org.openstreetmap.josm.testutils.JOSMTestRules;
import org.openstreetmap.josm.testutils.mockers.WindowMocker;
import org.openstreetmap.josm.tools.Territories;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import mockit.Mock;
import mockit.MockUp;

public class MapWithAIMoveActionTest {
    MapWithAIMoveAction moveAction;
    DataSet mapWithAIData;
    OsmDataLayer osmLayer;
    Way way1;
    Way way2;

    @Rule
    @SuppressFBWarnings("URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD")
    public JOSMTestRules test = new JOSMTestRules().preferences().main().projection().territories();

    @Before
    public void setUp() {
        moveAction = new MapWithAIMoveAction();
        final DataSet osmData = new DataSet();
        mapWithAIData = new DataSet();
        way1 = TestUtils.newWay("highway=residential", new Node(new LatLon(0, 0)), new Node(new LatLon(0.1, 0.1)));
        way2 = TestUtils.newWay("highway=residential", new Node(new LatLon(-0.1, -0.1)),
                new Node(new LatLon(0.1, 0.1)));
        way1.getNodes().forEach(node -> mapWithAIData.addPrimitive(node));
        way2.getNodes().forEach(node -> osmData.addPrimitive(node));
        osmData.addPrimitive(way2);
        mapWithAIData.addPrimitive(way1);
        way2.setOsmId(1, 1);
        way2.firstNode().setOsmId(1, 1);
        way2.lastNode().setOsmId(2, 1);

        osmLayer = new OsmDataLayer(osmData, "osm", null);
        final MapWithAILayer mapWithAILayer = new MapWithAILayer(mapWithAIData, "MapWithAI", null);
        MainApplication.getLayerManager().addLayer(osmLayer);
        MainApplication.getLayerManager().addLayer(mapWithAILayer);
        MainApplication.getLayerManager().setActiveLayer(mapWithAILayer);
    }

    @Test
    public void testMoveAction() {
        new MissingConnectionTagsMocker();

        mapWithAIData.addSelected(way1);
        moveAction.actionPerformed(null);
        assertEquals(osmLayer, MainApplication.getLayerManager().getActiveLayer(),
                "Current layer should be the OMS layer");
        assertNotNull(osmLayer.getDataSet().getPrimitiveById(way1), "way1 should have been added to the OSM layer");
        while (UndoRedoHandler.getInstance().hasUndoCommands()) {
            UndoRedoHandler.getInstance().undo();
        }
        assertNull(osmLayer.getDataSet().getPrimitiveById(way1), "way1 should have been removed from the OSM layer");
    }

    @Test
    public void testMoveEmptyAction() {
        Assertions.assertDoesNotThrow(() -> moveAction.actionPerformed(null));
    }

    @Test
    public void testConflationDupeKeyRemoval() {
        new MissingConnectionTagsMocker();
        mapWithAIData.unlock();
        way1.lastNode().put(DuplicateCommand.KEY, "n" + Long.toString(way2.lastNode().getUniqueId()));
        mapWithAIData.lock();
        mapWithAIData.addSelected(way1);
        final DataSet ds = osmLayer.getDataSet();

        moveAction.actionPerformed(null);
        Awaitility.await().atMost(Durations.ONE_SECOND).until(() -> ds.getPrimitiveById(way1) != null);
        assertTrue(((Way) ds.getPrimitiveById(way1)).lastNode().equals(((Way) ds.getPrimitiveById(way2)).lastNode()),
                "The duplicate node should have been replaced");
        assertFalse(((Way) ds.getPrimitiveById(way2)).lastNode().hasKey(DuplicateCommand.KEY),
                "The dupe key should no longer exist");
        assertFalse(((Way) ds.getPrimitiveById(way1)).lastNode().hasKey(DuplicateCommand.KEY),
                "The dupe key should no longer exist");

        UndoRedoHandler.getInstance().undo();
        Awaitility.await().atMost(Durations.ONE_SECOND)
                .until(() -> !((Way) ds.getPrimitiveById(way2)).lastNode().hasKey(DuplicateCommand.KEY));
        assertFalse(way2.lastNode().hasKey(DuplicateCommand.KEY), "The dupe key should no longer exist");
        assertTrue(way1.lastNode().hasKey(DuplicateCommand.KEY), "The dupe key should no longer exist");
    }

    @Test
    public void testConflationConnKeyRemoval() {
        new MissingConnectionTagsMocker();
        mapWithAIData.unlock();
        way1.lastNode().put(ConnectedCommand.KEY, "w" + Long.toString(way2.getUniqueId()) + ",n"
                + Long.toString(way2.lastNode().getUniqueId()) + ",n" + Long.toString(way2.firstNode().getUniqueId()));
        mapWithAIData.lock();
        mapWithAIData.addSelected(way1);

        moveAction.actionPerformed(null);
        Awaitility.await().atMost(Durations.ONE_SECOND).until(() -> way1.isDeleted());
        assertFalse(way2.lastNode().hasKey(ConnectedCommand.KEY), "The conn key should have been removed");
        assertFalse(way2.firstNode().hasKey(ConnectedCommand.KEY), "The conn key should have been removed");
        assertFalse(way2.getNode(1).hasKey(ConnectedCommand.KEY), "The conn key should have been removed");
        assertTrue(way1.isDeleted(), "way1 should be deleted when added");

        UndoRedoHandler.getInstance().undo();
        Awaitility.await().atMost(Durations.ONE_SECOND).until(() -> !way1.isDeleted() && !way1.lastNode().isDeleted());
        assertFalse(way2.lastNode().hasKey(ConnectedCommand.KEY), "The conn key shouldn't exist");
        assertTrue(way1.lastNode().hasKey(ConnectedCommand.KEY), "The conn key should exist");
        assertFalse(way1.lastNode().isDeleted(), "way1 should no longer be deleted");
    }

    private static class NotificationMocker extends MockUp<Notification> {
        public boolean shown;

        @Mock
        public void show() {
            shown = true;
        }
    }

    @Test
    public void testMaxAddNotification() {
        TestUtils.assumeWorkingJMockit();
        new WindowMocker();
        new MissingConnectionTagsMocker();

        NotificationMocker notification = new NotificationMocker();
        DataSet ds = MapWithAIDataUtils.getLayer(true).getDataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(new DataSet(), "TEST", null));
        MapWithAIPreferenceHelper.setMaximumAddition(1, false);
        for (int i = 0; i < 40; i++) {
            ds.addPrimitive(new Node(LatLon.ZERO));
        }
        for (int i = 0; i < 11; i++) {
            GuiHelper
                    .runInEDTAndWaitWithException(() -> ds.setSelected(ds.allNonDeletedPrimitives().iterator().next()));
            moveAction.actionPerformed(null);
        }
        assertTrue(notification.shown);
        notification.shown = false;
    }

    /**
     * This is a non-regression test. There used to be an AssertionError when an
     * address and building were added, and then was undone. See
     * <a href="https://gitlab.com/gokaart/JOSM_MapWithAI/-/issues/79">Issue #79</a>
     */
    @Test
    public void testBuildingAndAddressAdd() {
        // Required to avoid an NPE in Territories.getRegionalTaginfoUrls
        Future<?> territoriesRegionalTaginfo = MainApplication.worker.submit(() -> Territories.initialize());
        DataSet ds = MapWithAIDataUtils.getLayer(true).getDataSet();
        Way building = TestUtils.newWay("building=yes", new Node(new LatLon(38.236811, -104.62571)),
                new Node(new LatLon(38.236811, -104.625493)), new Node(new LatLon(38.236716, -104.625493)),
                new Node(new LatLon(38.236716, -104.62571)));
        building.getNodes().forEach(ds::addPrimitive);
        ds.addPrimitive(building);
        building.addNode(building.firstNode());
        Node address = TestUtils.newNode(
                "addr:city=Pueblo addr:housenumber=1901 addr:postcode=81004 addr:street=\"Lake Avenue\" mapwithai:source=\"Statewide Aggregate Addresses in Colorado 2019 (Public)\"");
        address.setCoor(new LatLon(38.2367599, -104.6255641));
        ds.addPrimitive(address);

        DataSet ds2 = new DataSet();
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds2, "TEST LAYER", null));

        // The building/address need to be selected separately
        // This is due to only allowing 1 additional selection at a time.
        ds.setSelected(building);
        ds.addSelected(address);
        // Wait for territories to finish
        assertDoesNotThrow(() -> territoriesRegionalTaginfo.get());
        GuiHelper.runInEDTAndWaitWithException(() -> moveAction.actionPerformed(null));
        while (UndoRedoHandler.getInstance().hasUndoCommands()) {
            assertDoesNotThrow(() -> UndoRedoHandler.getInstance().undo());
        }
    }
}
