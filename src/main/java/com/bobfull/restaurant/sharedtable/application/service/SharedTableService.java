package com.bobfull.restaurant.sharedtable.application.service;

import com.bobfull.common.exception.CommonErrorCode;
import com.bobfull.common.exception.CustomException;
import com.bobfull.common.exception.RestaurantErrorCode;
import com.bobfull.common.exception.SharedTableErrorCode;
import com.bobfull.common.response.PageResponse;
import com.bobfull.restaurant.restaurant.domain.entity.Restaurant;
import com.bobfull.restaurant.restaurant.infrastructure.repository.RestaurantRepository;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableIdResponse;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableBulkRequest;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableBulkResponse;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableRequest;
import com.bobfull.restaurant.sharedtable.presentation.dto.SharedTableResponse;
import com.bobfull.restaurant.sharedtable.domain.entity.SharedTable;
import com.bobfull.restaurant.sharedtable.infrastructure.repository.SharedTableRepository;
import java.time.Clock;
import java.util.Set;
import java.util.List;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OWNER 합석 테이블 등록·조회·수정·삭제를 담당한다.
 */
@Service
public class SharedTableService {

    private static final Logger log = LoggerFactory.getLogger(SharedTableService.class);
    private static final Set<Integer> ALLOWED_CAPACITIES = Set.of(2, 4, 6, 8);

    private final SharedTableRepository sharedTableRepository;
    private final RestaurantRepository restaurantRepository;
    private final SharedTableUsageValidator sharedTableUsageValidator;
    private final Clock clock;

    public SharedTableService(
            SharedTableRepository sharedTableRepository,
            RestaurantRepository restaurantRepository,
            SharedTableUsageValidator sharedTableUsageValidator,
            Clock clock
    ) {
        this.sharedTableRepository = sharedTableRepository;
        this.restaurantRepository = restaurantRepository;
        this.sharedTableUsageValidator = sharedTableUsageValidator;
        this.clock = clock;
    }

    @Transactional
    public SharedTableIdResponse register(Long ownerMemberId, Long restaurantId, SharedTableRequest request) {
        Restaurant restaurant = findActiveRestaurantForUpdateOrThrow(restaurantId);
        validateOwnership(restaurant, ownerMemberId);
        validateCapacity(request.capacity());

        int nextDisplayNumber = findNextDisplayNumber(restaurantId);
        SharedTable sharedTable = SharedTable.create(restaurantId, nextDisplayNumber, request.capacity());
        SharedTable savedTable = sharedTableRepository.save(sharedTable);
        return SharedTableIdResponse.from(savedTable);
    }

    @Transactional
    public SharedTableBulkResponse registerBulk(
            Long ownerMemberId,
            Long restaurantId,
            SharedTableBulkRequest request
    ) {
        Restaurant restaurant = findActiveRestaurantForUpdateOrThrow(restaurantId);
        validateOwnership(restaurant, ownerMemberId);
        validateCapacity(request.capacity());
        validateCount(request.count());

        int firstDisplayNumber = findNextDisplayNumber(restaurantId);
        List<SharedTable> tables = IntStream.range(0, request.count())
                .mapToObj(offset -> SharedTable.create(
                        restaurantId,
                        firstDisplayNumber + offset,
                        request.capacity()
                ))
                .toList();

        return SharedTableBulkResponse.from(sharedTableRepository.saveAll(tables));
    }

    @Transactional(readOnly = true)
    public PageResponse<SharedTableResponse> getTables(Long ownerMemberId, Long restaurantId, Pageable pageable) {
        Restaurant restaurant = findActiveRestaurantOrThrow(restaurantId);
        validateOwnership(restaurant, ownerMemberId);

        Page<SharedTable> tables = sharedTableRepository.findAllByRestaurantIdAndDeletedAtIsNull(restaurantId, pageable);
        return PageResponse.from(tables.map(SharedTableResponse::from));
    }

    @Transactional(readOnly = true)
    public SharedTableResponse getTable(Long ownerMemberId, Long tableId) {
        SharedTable sharedTable = findActiveTableOrThrow(tableId);
        validateRestaurantOwnership(sharedTable.getRestaurantId(), ownerMemberId);
        return SharedTableResponse.from(sharedTable);
    }

    @Transactional
    public SharedTableIdResponse update(Long ownerMemberId, Long tableId, SharedTableRequest request) {
        SharedTable sharedTable = findActiveTableOrThrow(tableId);
        validateRestaurantOwnership(sharedTable.getRestaurantId(), ownerMemberId);
        validateCapacity(request.capacity());
        sharedTableUsageValidator.validateCapacityChangeAllowed(sharedTable.getId());

        Integer beforeCapacity = sharedTable.getCapacity();
        sharedTable.updateCapacity(request.capacity());
        if (!beforeCapacity.equals(sharedTable.getCapacity())) {
            log.info("event=TABLE_CAPACITY_CHANGED tableId={} restaurantId={} actorId={} beforeCapacity={} afterCapacity={}",
                    sharedTable.getId(), sharedTable.getRestaurantId(), ownerMemberId, beforeCapacity,
                    sharedTable.getCapacity());
        }
        return SharedTableIdResponse.from(sharedTable);
    }

    @Transactional
    public SharedTableIdResponse delete(Long ownerMemberId, Long tableId) {
        SharedTable sharedTable = findActiveTableOrThrow(tableId);
        validateRestaurantOwnership(sharedTable.getRestaurantId(), ownerMemberId);
        sharedTableUsageValidator.validateDeletionAllowed(sharedTable.getId());

        sharedTable.softDelete(clock.instant());
        return SharedTableIdResponse.from(sharedTable);
    }

    private SharedTable findActiveTableOrThrow(Long tableId) {
        return sharedTableRepository.findByIdAndDeletedAtIsNull(tableId)
                .orElseThrow(() -> new CustomException(SharedTableErrorCode.TABLE_ID_NOT_FOUND));
    }

    private void validateRestaurantOwnership(Long restaurantId, Long ownerMemberId) {
        Restaurant restaurant = findActiveRestaurantOrThrow(restaurantId);
        validateOwnership(restaurant, ownerMemberId);
    }

    private Restaurant findActiveRestaurantOrThrow(Long restaurantId) {
        return restaurantRepository.findByIdAndDeletedAtIsNull(restaurantId)
                .orElseThrow(() -> new CustomException(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND));
    }

    private Restaurant findActiveRestaurantForUpdateOrThrow(Long restaurantId) {
        return restaurantRepository.findByIdAndDeletedAtIsNullForUpdate(restaurantId)
                .orElseThrow(() -> new CustomException(RestaurantErrorCode.RESTAURANT_ID_NOT_FOUND));
    }

    private int findNextDisplayNumber(Long restaurantId) {
        return sharedTableRepository.findMaxDisplayNumberByRestaurantId(restaurantId) + 1;
    }

    private void validateOwnership(Restaurant restaurant, Long ownerMemberId) {
        if (!restaurant.isOwnedBy(ownerMemberId)) {
            throw new CustomException(CommonErrorCode.ACCESS_DENIED);
        }
    }

    private void validateCapacity(Integer capacity) {
        if (capacity == null || !ALLOWED_CAPACITIES.contains(capacity)) {
            throw new CustomException(SharedTableErrorCode.INVALID_TABLE_CAPACITY);
        }
    }

    private void validateCount(Integer count) {
        if (count == null || count < 1 || count > 10) {
            throw new CustomException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }
}
