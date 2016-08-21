package za.org.grassroot.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.org.grassroot.core.domain.*;
import za.org.grassroot.core.repository.GroupRepository;
import za.org.grassroot.core.repository.TodoRequestRepository;
import za.org.grassroot.core.repository.UidIdentifiableRepository;
import za.org.grassroot.core.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

import static za.org.grassroot.core.util.DateTimeUtil.*;

@Service
public class TodoRequestBrokerImpl implements TodoRequestBroker {
	private final Logger logger = LoggerFactory.getLogger(TodoRequestBrokerImpl.class);

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private GroupRepository groupRepository;
	@Autowired
	private TodoBroker todoBroker;
	@Autowired
	private PermissionBroker permissionBroker;
	@Autowired
	private TodoRequestRepository todoRequestRepository;
	@Autowired
	UidIdentifiableRepository genericEntityRepository;

	@Override
	public TodoRequest load(String requestUid) {
		return todoRequestRepository.findOneByUid(requestUid);
	}

	@Override
	@Transactional
	public TodoRequest create(String userUid, String groupUid) {
		Objects.requireNonNull(userUid);
		Objects.requireNonNull(groupUid);

		User user = userRepository.findOneByUid(userUid);
		Group group = groupRepository.findOneByUid(groupUid);

		TodoRequest request = TodoRequest.makeEmpty(user, group);

		todoRequestRepository.save(request);

		return request;
	}

	@Override
	@Transactional
	public TodoRequest create(String userUid, String parentUid, JpaEntityType parentType, String message, LocalDateTime deadline, int reminderMinutes, boolean replicateToSubGroups) {
		Objects.requireNonNull(userUid);
		Objects.requireNonNull(parentUid);
		Objects.requireNonNull(message);
		Objects.requireNonNull(deadline);
		Objects.requireNonNull(reminderMinutes);

		User user = userRepository.findOneByUid(userUid);
		TodoContainer parent = genericEntityRepository.findOneByUid(TodoContainer.class, parentType, parentUid);

		if (parent instanceof Group)
			permissionBroker.validateGroupPermission(user, (Group) parent, Permission.GROUP_PERMISSION_CREATE_LOGBOOK_ENTRY);

		TodoRequest todoRequest = TodoRequest.makeEmpty(user, parent);
		todoRequest.setMessage(message);
		todoRequest.setActionByDate(convertToSystemTime(deadline, getSAST()));
		todoRequest.setReminderMinutes(reminderMinutes);
		todoRequest.setReplicateToSubgroups(replicateToSubGroups);

		todoRequestRepository.save(todoRequest);

		logger.info("Leaving create request ... parent is: " + todoRequest.getParent());

		return todoRequest;
	}

	@Override
    @Transactional
	public void updateMessage(String userUid, String requestUid, String message) {
		Objects.requireNonNull(userUid);
		Objects.requireNonNull(requestUid);

        User user = userRepository.findOneByUid(userUid);
        TodoRequest todoRequest = todoRequestRepository.findOneByUid(requestUid);

        if (!todoRequest.getCreatedByUser().equals(user))
            throw new AccessDeniedException("You are not the creator of this Logbook");

        todoRequest.setMessage(message);
	}

    @Override
    @Transactional
    public void updateDueDate(String userUid, String requestUid, LocalDateTime dueDate) {
        Objects.requireNonNull(userUid);
        Objects.requireNonNull(requestUid);

        User user = userRepository.findOneByUid(userUid);
        TodoRequest todoRequest = todoRequestRepository.findOneByUid(requestUid);

        if (!todoRequest.getCreatedByUser().equals(user))
            throw new AccessDeniedException("You are not the creator of this logbook");

        todoRequest.setActionByDate(convertToSystemTime(dueDate, getSAST()));
    }

    @Override
	@Transactional
	public void finish(String logBookUid) {
		Objects.requireNonNull(logBookUid);

		TodoRequest todoRequest = todoRequestRepository.findOneByUid(logBookUid);

		// Since requests are only used in the USSD, and since we are stripping user assignment from USSD as too compelx
        // for both users and design, am defaulting this to whole group for now

        // Set<String> assignedMemberUids = todoRequest.getAssignedMembers().stream().map(User::getUid).collect(Collectors.toSet());
		Set<String> assignedMemberUids = Collections.emptySet();
        TodoContainer parent = todoRequest.getParent();

		LocalDateTime actionDate = LocalDateTime.from(todoRequest.getActionByDate().atZone(getSAST()));
		todoBroker.create(todoRequest.getCreatedByUser().getUid(), parent.getJpaEntityType(), parent.getUid(),
				todoRequest.getMessage(), actionDate, todoRequest.getReminderMinutes(),
				todoRequest.isReplicateToSubgroups(), assignedMemberUids);

		todoRequestRepository.delete(todoRequest);
	}
}
