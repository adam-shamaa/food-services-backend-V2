package com.foodservicesapi.services;

import com.foodservicesapi.mappers.RepositoryMapper;
import com.foodservicesapi.models.domain.Address;
import com.foodservicesapi.models.domain.PairedRestaurantOverview;
import com.foodservicesapi.models.domain.RestaurantOverview;
import com.foodservicesapi.models.repositories.Restaurant;
import com.foodservicesapi.models.repositories.RestaurantsSearchResult;
import com.foodservicesapi.repositories.RestaurantsSearchRepository;
import com.foodservicesapi.services.skipthedishes.SkipTheDishesService;
import com.foodservicesapi.services.ubereats.UberEatsService;
import lombok.RequiredArgsConstructor;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RestaurantsService {

  @Value("${server.country}")
  private String countryOfOperation;

  private final RestaurantUtils restaurantUtils;
  private final SkipTheDishesService skipTheDishesService;
  private final UberEatsService uberEatsService;
  private final RestaurantsSearchRepository restaurantRepository;
  private final RepositoryMapper repositoryMapper = Mappers.getMapper(RepositoryMapper.class);

  /* ***********************************************  RestaurantLists  ************************************************ */

  public List<PairedRestaurantOverview> getRestaurants(Address address, String searchQuery) {
    List<ServiceProvider> availableServiceProviders = getServiceProviders();
    List<RestaurantOverview> availableRestaurants = fetchAndJoinRestaurantsFromServiceProviders(address, searchQuery, availableServiceProviders);
    Collection<List<RestaurantOverview>> listOfGroupedRestaurants = groupRestaurantOverviewsByAttributes(availableRestaurants);
    List<PairedRestaurantOverview> groupedRestaurantsDomainObjectList = groupedRestaurantsToPairedRestaurants(listOfGroupedRestaurants);
    return groupedRestaurantsDomainObjectList;
  }

  // ********************************************************************************************************************

  public boolean saveRestaurantsSearchResult(RestaurantsSearchResult restaurantsSearchResult) {
    restaurantRepository.insert(restaurantsSearchResult);
    return true;
  }

  // ********************************************************************************************************************

  private Collection<List<RestaurantOverview>> groupRestaurantOverviewsByAttributes(List<RestaurantOverview> restaurantOverviewList) {
    Map<String, List<RestaurantOverview>> restaurantsNameMapping = new HashMap<>();
    for (RestaurantOverview restaurantOverview : restaurantOverviewList) {
      String sanitizedRestaurantName = restaurantUtils.normalizeName(restaurantOverview.getName());
      restaurantsNameMapping.putIfAbsent(sanitizedRestaurantName, new ArrayList<>());
      restaurantsNameMapping.get(sanitizedRestaurantName).add(restaurantOverview);
    }
    return restaurantsNameMapping.values();
  }

  private List<PairedRestaurantOverview> groupedRestaurantsToPairedRestaurants(Collection<List<RestaurantOverview>> collectionOfGroupedRestaurants) {
    return collectionOfGroupedRestaurants.stream()
        .map(
            groupedRestaurantsList ->
                PairedRestaurantOverview.builder()
                    .id(UUID.randomUUID().toString())
                    .serviceProviderRestaurants(groupedRestaurantsList)
                    .build())
        .collect(Collectors.toList());
  }

  // ********************************************************************************************************************

  private List<RestaurantOverview> fetchAndJoinRestaurantsFromServiceProviders(
      Address address, String searchQuery, List<ServiceProvider> serviceProviderList) {
    return joinRestaurantOverviewListCompletableFutures(
        fetchRestaurantsFromServiceProvidersList(address, searchQuery, serviceProviderList));
  }

  private List<CompletableFuture<List<RestaurantOverview>>>
      fetchRestaurantsFromServiceProvidersList(
          Address address, String searchQuery, List<ServiceProvider> serviceProviderList) {
    return serviceProviderList.stream()
        .map(serviceProvider -> serviceProvider.retrieveRestaurants(address, searchQuery))
        .collect(Collectors.toList());
  }

  private List<RestaurantOverview> joinRestaurantOverviewListCompletableFutures(
      List<CompletableFuture<List<RestaurantOverview>>> completableFutureList) {
    return CompletableFuture.allOf(completableFutureList.toArray(new CompletableFuture[0]))
        .thenApply(
            voidObject ->
                completableFutureList.stream()
                    .map(CompletableFuture::join)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList()))
        .join();
  }

  // ********************************************************************************************************************

  // ********************************************************************************************************************
  public List<ServiceProvider> getServiceProviders() {
    switch (this.countryOfOperation) {
      case "CA":
        return Arrays.asList(skipTheDishesService, uberEatsService);
      default:
        throw new IllegalArgumentException();
    }
  }
  // ********************************************************************************************************************

  /**********************************************************************************************************************/

  /* ***********************************************  Restaurant Details  ************************************************ */
  public List<com.foodservicesapi.models.domain.Restaurant> getRestaurant(Address address, String id) {
    return fetchAndJoinRestaurantDetailsFromServiceProviders(id, address);
  }

  public List<com.foodservicesapi.models.domain.Restaurant> fetchAndJoinRestaurantDetailsFromServiceProviders(String restaurantID, Address address) {
    return joinRestaurantDetailsListCompletableFutures(
        fetchRestaurantDetailList(fetchRestaurant(restaurantID), address));
  }

  public List<CompletableFuture<com.foodservicesapi.models.domain.Restaurant>> fetchRestaurantDetailList(PairedRestaurantOverview pairedRestaurantOverview, Address address) {
    return pairedRestaurantOverview.getServiceProviderRestaurants().stream()
        .map(restaurantOverview -> fetchRestaurantDetail(restaurantOverview, address))
        .collect(Collectors.toList());
  }

  public CompletableFuture<com.foodservicesapi.models.domain.Restaurant> fetchRestaurantDetail(RestaurantOverview restaurantOverview, Address address) {
    switch (restaurantOverview.getServiceProvider()) {
      case SKIPTHEDISHES:
        return skipTheDishesService.retrieveRestaurant(restaurantOverview.getId(), address);
      case UBEREATS:
        return uberEatsService.retrieveRestaurant(restaurantOverview.getId(), address);
      default:
        throw new IllegalArgumentException();
    }
  }

  private List<com.foodservicesapi.models.domain.Restaurant> joinRestaurantDetailsListCompletableFutures(List<CompletableFuture<com.foodservicesapi.models.domain.Restaurant>> completableFutureList) {
    return CompletableFuture.allOf(completableFutureList.toArray(new CompletableFuture[0]))
        .thenApply(
            voidObject ->
                completableFutureList.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toList()))
        .join();
  }

  public PairedRestaurantOverview fetchRestaurant(String id) {
    List<RestaurantsSearchResult> restaurantsSearchResults = restaurantRepository.findByRestaurantList_Id(id);
    if (restaurantsSearchResults.size() != 1) {
      throw new IllegalArgumentException();
    }
    Restaurant restaurant = restaurantsSearchResults.get(0).getRestaurantList().stream().filter(res -> res.getId().equals(id)).findFirst().get();
    return repositoryMapper.toPairedRestaurantOverviewDomain(restaurant);
  }
}
