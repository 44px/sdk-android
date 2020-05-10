package ru.livetex.sdk.logic;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import androidx.annotation.Nullable;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import okio.ByteString;
import ru.livetex.sdk.entity.AttributesEntity;
import ru.livetex.sdk.entity.AttributesRequest;
import ru.livetex.sdk.entity.BaseEntity;
import ru.livetex.sdk.entity.Department;
import ru.livetex.sdk.entity.DepartmentRequestEntity;
import ru.livetex.sdk.entity.DialogState;
import ru.livetex.sdk.entity.EmployeeTypingEvent;
import ru.livetex.sdk.entity.HistoryEntity;
import ru.livetex.sdk.entity.ResponseEntity;
import ru.livetex.sdk.entity.TextMessage;
import ru.livetex.sdk.entity.TypingEvent;
import ru.livetex.sdk.network.NetworkManager;

public class LiveTexMessagesHandler {
	protected final String TAG = "MessagesHandler";

	private final PublishSubject<BaseEntity> entitySubject = PublishSubject.create();
	private final PublishSubject<DialogState> dialogStateSubject = PublishSubject.create();
	private final PublishSubject<HistoryEntity> historySubject = PublishSubject.create();
	private final PublishSubject<EmployeeTypingEvent> employeeTypingSubject = PublishSubject.create();
	private final PublishSubject<AttributesRequest> attributesRequestSubject = PublishSubject.create();
	private final PublishSubject<DepartmentRequestEntity> departmentRequestSubject = PublishSubject.create();
	protected final HashMap<String, Subject> subscriptions = new HashMap<>();

	protected EntityMapper mapper = new EntityMapper();

	public void init() {
		Disposable disposable = NetworkManager.getInstance().connectionState()
				.filter(state -> state == NetworkManager.ConnectionState.DISCONNECTED)
				.subscribe(ignore -> {
					Log.i(TAG, "Disconnect detected, clearing subscriptions");
					for (Subject subj : subscriptions.values()) {
						if (!subj.hasComplete()) {
							subj.onError(new IllegalStateException("Websocket disconnect"));
						}
					}
					subscriptions.clear();
				}, thr -> Log.e(TAG, "", thr));
	}

	public synchronized void onMessage(String text) {
		Log.d(TAG, "onMessage " + text);
		BaseEntity entity = null;

		try {
			entity = mapper.toEntity(text);
		} catch (Exception e) {
			Log.e(TAG, "Error when parsing message", e);
		}
		if (entity == null) {
			return;
		}

		entitySubject.onNext(entity);

		// Subjects to notify client
		if (entity instanceof DialogState) {
			dialogStateSubject.onNext((DialogState) entity);
		} else if (entity instanceof HistoryEntity) {
			historySubject.onNext((HistoryEntity) entity);
		} else if (entity instanceof EmployeeTypingEvent) {
			employeeTypingSubject.onNext((EmployeeTypingEvent) entity);
		} else if (entity instanceof AttributesRequest) {
			attributesRequestSubject.onNext((AttributesRequest) entity);
		} else if (entity instanceof DepartmentRequestEntity) {
			departmentRequestSubject.onNext((DepartmentRequestEntity) entity);
		}

		Subject subscription = subscriptions.get(entity.correlationId);

		if (subscription != null) {
			// Currently client need to handle only first response to request. Another "responses" have only human logic meaning.
			if (entity instanceof ResponseEntity) {
				if (!subscription.hasComplete()) {
					subscription.onNext(entity);
				}
				subscriptions.remove(entity.correlationId);
			}
		}
	}

	public void setMapper(EntityMapper mapper) {
		this.mapper = mapper;
	}

	public void sendTypingEvent(String text) {
		TypingEvent event = new TypingEvent(text);
		String json = EntityMapper.gson.toJson(event);
		sendJson(json);
	}

	public void sendAttributes(@Nullable String name,
							   @Nullable String phone,
							   @Nullable String email,
							   @Nullable Map<String, Object> attrs) {
		AttributesEntity event = new AttributesEntity(name, phone, email, attrs);
		String json = EntityMapper.gson.toJson(event);
		sendJson(json);
	}

	public Single<ResponseEntity> sendTextEvent(String text) {
		TextMessage event = new TextMessage(text);
		String json = EntityMapper.gson.toJson(event);
		return sendAndSubscribe(json, event.correlationId);
	}

	public Single<ResponseEntity> sendDepartmentSelectionEvent(String depId) {
		Department event = new Department(depId);
		String json = EntityMapper.gson.toJson(event);
		return sendAndSubscribe(json, event.correlationId);
	}

	public PublishSubject<BaseEntity> entity() {
		return entitySubject;
	}

	public PublishSubject<DialogState> dialogStateUpdate() {
		return dialogStateSubject;
	}

	public PublishSubject<HistoryEntity> history() {
		return historySubject;
	}

	public PublishSubject<EmployeeTypingEvent> employeeTyping() {
		return employeeTypingSubject;
	}

	public PublishSubject<AttributesRequest> attributesRequest() {
		return attributesRequestSubject;
	}

	public PublishSubject<DepartmentRequestEntity> departmentRequest() {
		return departmentRequestSubject;
	}

	public void onDataMessage(ByteString bytes) {
		// not used
	}

	private Single<ResponseEntity> sendAndSubscribe(String json, String correlationId) {
		Subject<ResponseEntity> subscription = PublishSubject.create();
		if (NetworkManager.getInstance().getWebSocket() != null) {
			subscriptions.put(correlationId, subscription);
			try {
				sendJson(json);
			} catch (Exception e) {
				return Single.error(e);
			}
			return subscription.take(1).singleOrError();
		} else {
			return Single.error(new IllegalStateException("Trying to send data when websocket is null"));
		}
	}

	private void sendJson(String json) {
		if (NetworkManager.getInstance().getWebSocket() != null) {
			Log.d(TAG, "Sending: " + json);
			NetworkManager.getInstance().getWebSocket().send(json);
		} else {
			throw new IllegalStateException("Trying to send data when websocket is null");
		}
	}
}
