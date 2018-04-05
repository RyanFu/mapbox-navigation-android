package com.mapbox.services.android.navigation.v5.navigation;

import android.location.Location;

import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.api.directions.v5.models.LegStep;
import com.mapbox.api.directions.v5.models.RouteLeg;
import com.mapbox.api.directions.v5.models.StepIntersection;
import com.mapbox.geojson.Point;
import com.mapbox.services.android.navigation.v5.offroute.OffRoute;
import com.mapbox.services.android.navigation.v5.offroute.OffRouteCallback;
import com.mapbox.services.android.navigation.v5.offroute.OffRouteDetector;
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress;
import com.mapbox.services.android.navigation.v5.utils.RouteUtils;

import java.util.List;

import static com.mapbox.services.android.navigation.v5.navigation.NavigationHelper.checkBearingForStepCompletion;
import static com.mapbox.services.android.navigation.v5.navigation.NavigationHelper.createDistancesToIntersections;
import static com.mapbox.services.android.navigation.v5.navigation.NavigationHelper.createIntersectionsList;
import static com.mapbox.services.android.navigation.v5.navigation.NavigationHelper.decodeStepPoints;
import static com.mapbox.services.android.navigation.v5.navigation.NavigationHelper.increaseIndex;
import static com.mapbox.services.android.navigation.v5.navigation.NavigationHelper.legDistanceRemaining;
import static com.mapbox.services.android.navigation.v5.navigation.NavigationHelper.routeDistanceRemaining;
import static com.mapbox.services.android.navigation.v5.navigation.NavigationHelper.stepDistanceRemaining;
import static com.mapbox.services.android.navigation.v5.navigation.NavigationHelper.userSnappedToRoutePosition;

class NavigationRouteProcessor implements OffRouteCallback {

  private static final int FIRST_LEG_INDEX = 0;
  private static final int FIRST_STEP_INDEX = 0;

  private RouteProgress routeProgress;
  private List<Point> currentStepPoints;
  private List<Point> upcomingStepPoints;
  private List<StepIntersection> currentIntersections;
  private List<Double> currentIntersectionDistances;
  private NavigationIndices indices;
  private boolean shouldIncreaseIndex;

  NavigationRouteProcessor() {
    indices = NavigationIndices.create(FIRST_LEG_INDEX, FIRST_STEP_INDEX);
  }

  @Override
  public void onShouldIncreaseIndex() {
    shouldIncreaseIndex = true;
  }

  /**
   * Will take a given location update and create a new {@link RouteProgress}
   * based on our calculations of the distances remaining.
   * <p>
   * Also in charge of detecting if a step / leg has finished and incrementing the
   * indices if needed ({@link NavigationRouteProcessor#advanceIndices(MapboxNavigation)} handles
   * the decoding of the next step point list).
   *
   * @param mapboxNavigation for the current route / options
   * @param location         for step / leg / route distance remaining
   * @return new route progress along the route
   */
  RouteProgress buildNewRouteProgress(MapboxNavigation mapboxNavigation, Location location) {
    DirectionsRoute directionsRoute = mapboxNavigation.getRoute();
    MapboxNavigationOptions options = mapboxNavigation.options();
    double completionOffset = options.maxTurnCompletionOffset();
    double maneuverZoneRadius = options.maneuverZoneRadius();

    checkNewRoute(mapboxNavigation);

    double stepDistanceRemaining = calculateStepDistanceRemaining(location, directionsRoute);
    boolean withinManeuverRadius = stepDistanceRemaining < maneuverZoneRadius;
    boolean bearingMatchesManeuver = checkBearingForStepCompletion(
      location, routeProgress, stepDistanceRemaining, completionOffset
    );
    boolean forceIncreaseIndices = stepDistanceRemaining == 0 && !bearingMatchesManeuver;

    if ((bearingMatchesManeuver && withinManeuverRadius) || forceIncreaseIndices) {
      advanceIndices(mapboxNavigation);
      stepDistanceRemaining = calculateStepDistanceRemaining(location, directionsRoute);
    }

    return assembleRouteProgress(directionsRoute, stepDistanceRemaining);
  }

  RouteProgress getRouteProgress() {
    return routeProgress;
  }

  void setRouteProgress(RouteProgress routeProgress) {
    this.routeProgress = routeProgress;
  }

  /**
   * If the {@link OffRouteCallback#onShouldIncreaseIndex()} has been called by the
   * {@link com.mapbox.services.android.navigation.v5.offroute.OffRouteDetector}, shouldIncreaseIndex
   * will be true and the {@link NavigationIndices} index needs to be increased by one.
   *
   * @param navigation to get the next {@link LegStep#geometry()} and off-route engine
   */
  void checkIncreaseIndex(MapboxNavigation navigation) {
    if (shouldIncreaseIndex) {
      advanceIndices(navigation);
      shouldIncreaseIndex = false;
    }
  }

  /**
   * Increases the step index in {@link NavigationIndices} by 1.
   * <p>
   * Decodes the step points for the new step and clears the distances from
   * maneuver stack, as the maneuver has now changed.
   *
   * @param mapboxNavigation to get the next {@link LegStep#geometry()} and {@link OffRoute}
   */
  private void advanceIndices(MapboxNavigation mapboxNavigation) {
    indices = increaseIndex(routeProgress, indices);
    processNewIndex(mapboxNavigation);
  }

  private RouteProgress assembleRouteProgress(DirectionsRoute directionsRoute, double stepDistanceRemaining) {
    int legIndex = indices.legIndex();
    int stepIndex = indices.stepIndex();
    double legDistanceRemaining = legDistanceRemaining(stepDistanceRemaining, legIndex, stepIndex, directionsRoute);
    double routeDistanceRemaining = routeDistanceRemaining(legDistanceRemaining, legIndex, directionsRoute);

    RouteProgress.Builder progressBuilder = RouteProgress.builder()
      .stepDistanceRemaining(stepDistanceRemaining)
      .legDistanceRemaining(legDistanceRemaining)
      .distanceRemaining(routeDistanceRemaining)
      .directionsRoute(directionsRoute)
      .currentStepPoints(currentStepPoints)
      .upcomingStepPoints(upcomingStepPoints)
      .stepIndex(stepIndex)
      .legIndex(legIndex)
      .intersections(currentIntersections)
      .intersectionDistancesAlongStep(currentIntersectionDistances);

    if (upcomingStepPoints != null && !upcomingStepPoints.isEmpty()) {
      progressBuilder.upcomingStepPoints(upcomingStepPoints);
    }
    return progressBuilder.build();
  }

  private void processNewIndex(MapboxNavigation mapboxNavigation) {
    DirectionsRoute route = mapboxNavigation.getRoute();
    int legIndex = indices.legIndex();
    int stepIndex = indices.stepIndex();
    int upcomingStepIndex = stepIndex + 1;
    List<LegStep> steps = route.legs().get(legIndex).steps();
    LegStep currentStep = steps.get(stepIndex);
    LegStep upcomingStep = upcomingStepIndex < steps.size() ? steps.get(stepIndex) : null;
    currentStepPoints = decodeStepPoints(route, currentStepPoints, legIndex, stepIndex);
    upcomingStepPoints = decodeStepPoints(route, upcomingStepPoints, legIndex, upcomingStepIndex);
    currentIntersections = createIntersectionsList(currentStep, upcomingStep);
    currentIntersectionDistances = createDistancesToIntersections(currentStepPoints, currentIntersections);
    clearManeuverDistances(mapboxNavigation.getOffRouteEngine());
  }

  /**
   * Checks if the route provided is a new route.  If it is, all {@link RouteProgress}
   * data and {@link NavigationIndices} needs to be reset.
   *
   * @param mapboxNavigation to get the current route and off-route engine
   */
  private void checkNewRoute(MapboxNavigation mapboxNavigation) {
    DirectionsRoute directionsRoute = mapboxNavigation.getRoute();
    if (RouteUtils.isNewRoute(routeProgress, directionsRoute)) {

      indices = NavigationIndices.create(FIRST_LEG_INDEX, FIRST_STEP_INDEX);
      processNewIndex(mapboxNavigation);

      RouteLeg firstLeg = directionsRoute.legs().get(FIRST_LEG_INDEX);
      double stepDistanceRemaining = firstLeg.steps().get(FIRST_STEP_INDEX).distance();
      routeProgress = assembleRouteProgress(directionsRoute, stepDistanceRemaining);
    }
  }

  /**
   * Given a location update, calculate the current step distance remaining.
   *
   * @param location        for current coordinates
   * @param directionsRoute for current {@link LegStep}
   * @return distance remaining in meters
   */
  private double calculateStepDistanceRemaining(Location location, DirectionsRoute directionsRoute) {
    Point snappedPosition = userSnappedToRoutePosition(location, currentStepPoints);
    return stepDistanceRemaining(
      snappedPosition, indices.legIndex(), indices.stepIndex(), directionsRoute, currentStepPoints
    );
  }

  private void clearManeuverDistances(OffRoute offRoute) {
    if (offRoute instanceof OffRouteDetector) {
      ((OffRouteDetector) offRoute).clearDistancesAwayFromManeuver();
    }
  }
}
