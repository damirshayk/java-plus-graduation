package ru.practicum.ewm.stats.collector.controller;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import ru.practicum.ewm.stats.collector.service.UserActionService;
import ru.practicum.ewm.stats.proto.UserActionProto;
import ru.practicum.ewm.stats.proto.collector.UserActionControllerGrpc;

@GrpcService
public class UserActionController extends UserActionControllerGrpc.UserActionControllerImplBase {

    private final UserActionService userActionService;

    public UserActionController(UserActionService userActionService) {
        this.userActionService = userActionService;
    }

    @Override
    public void collectUserAction(UserActionProto request, StreamObserver<Empty> responseObserver) {
        try {
            userActionService.collectUserAction(request).whenComplete((result, error) -> {
                if (error != null) {
                    responseObserver.onError(Status.UNAVAILABLE
                            .withDescription("Не удалось сохранить действие пользователя").asRuntimeException());
                    return;
                }
                responseObserver.onNext(Empty.getDefaultInstance());
                responseObserver.onCompleted();
            });
        } catch (IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(exception.getMessage())
                    .asRuntimeException());
        }
    }
}
