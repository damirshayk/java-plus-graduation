package ru.practicum.ewm.mapper;

import org.mapstruct.*;
import ru.practicum.ewm.dto.event.*;
import ru.practicum.ewm.model.Event;
import ru.practicum.ewm.model.Location;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EventMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "initiator", ignore = true)
    @Mapping(target = "createdOn", ignore = true)
    @Mapping(target = "publishedOn", ignore = true)
    @Mapping(target = "state", ignore = true)
    @Mapping(target = "isPaid", source = "paid")
    @Mapping(target = "isRequestModeration", source = "requestModeration")
    Event toEvent(NewEventDto dto);

    EventShortDto toShortDto(
            Event event,
            long confirmedRequests,
            long views
    );

    EventFullDto toFullDto(
            Event event,
            long confirmedRequests,
            long views
    );

    @BeanMapping(
            nullValuePropertyMappingStrategy =
                    NullValuePropertyMappingStrategy.IGNORE
    )
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "initiator", ignore = true)
    @Mapping(target = "createdOn", ignore = true)
    @Mapping(target = "publishedOn", ignore = true)
    @Mapping(target = "state", ignore = true)
    @Mapping(
            target = "location",
            source = "location",
            qualifiedByName = "copyLocation"
    )
    @Mapping(target = "paid", source = "isPaid")
    @Mapping(target = "requestModeration", source = "isRequestModeration")
    void updateFromUserRequest(
            UpdateEventUserRequest request,
            @MappingTarget Event event
    );

    @BeanMapping(
            nullValuePropertyMappingStrategy =
                    NullValuePropertyMappingStrategy.IGNORE
    )
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "category", ignore = true)
    @Mapping(target = "initiator", ignore = true)
    @Mapping(target = "createdOn", ignore = true)
    @Mapping(target = "publishedOn", ignore = true)
    @Mapping(target = "state", ignore = true)
    @Mapping(
            target = "location",
            source = "location",
            qualifiedByName = "copyLocation"
    )
    @Mapping(target = "requestModeration", source = "isRequestModeration")
    @Mapping(target = "paid", source = "isPaid")
    void updateFromAdminRequest(
            UpdateEventAdminRequest request,
            @MappingTarget Event event
    );

    @Named("copyLocation")
    Location copyLocation(Location location);
}
