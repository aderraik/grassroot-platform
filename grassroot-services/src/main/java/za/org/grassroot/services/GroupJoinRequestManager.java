package za.org.grassroot.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.org.grassroot.core.domain.Group;
import za.org.grassroot.core.domain.GroupJoinRequest;
import za.org.grassroot.core.domain.GroupJoinRequestEvent;
import za.org.grassroot.core.domain.User;
import za.org.grassroot.core.enums.GroupJoinRequestEventType;
import za.org.grassroot.core.enums.GroupJoinRequestStatus;
import za.org.grassroot.core.repository.GroupJoinRequestEventRepository;
import za.org.grassroot.core.repository.GroupJoinRequestRepository;
import za.org.grassroot.core.repository.GroupRepository;
import za.org.grassroot.core.repository.UserRepository;

import java.time.Instant;

@Component
public class GroupJoinRequestManager implements GroupJoinRequestService {
    private final Logger logger = LoggerFactory.getLogger(GroupJoinRequestManager.class);

    private final GroupRepository groupRepository;
    private final UserRepository userRepository;
    private final GroupJoinRequestRepository groupJoinRequestRepository;
    private final GroupJoinRequestEventRepository groupJoinRequestEventRepository;

    @Autowired
    public GroupJoinRequestManager(GroupRepository groupRepository,
                                   UserRepository userRepository,
                                   GroupJoinRequestRepository groupJoinRequestRepository,
                                   GroupJoinRequestEventRepository groupJoinRequestEventRepository) {
        this.groupRepository = groupRepository;
        this.userRepository = userRepository;
        this.groupJoinRequestRepository = groupJoinRequestRepository;
        this.groupJoinRequestEventRepository = groupJoinRequestEventRepository;
    }

    @Override
    @Transactional
    public String open(String requestorUid, String groupUid) {
        User requestor = userRepository.findOneByUid(requestorUid);
        Group group = groupRepository.findOneByUid(groupUid);

        logger.info("Opening new group join request: requestor={}, group={}", requestor, group);

        Instant time = Instant.now();
        GroupJoinRequest request = new GroupJoinRequest(requestor, group, time);
        groupJoinRequestRepository.save(request);

        GroupJoinRequestEvent event = new GroupJoinRequestEvent(GroupJoinRequestEventType.OPENED, request, requestor, time);
        groupJoinRequestEventRepository.save(event);

        logger.info("Group join request opened: {}", request);
        return request.getUid();
    }

    @Override
    @Transactional
    public void approve(String userUid, String requestUid) {
        User user = userRepository.findOneByUid(userUid);
        GroupJoinRequest request = groupJoinRequestRepository.findOneByUid(requestUid);

        logger.info("Approving request: request={}, user={}", request, user);
        Instant time = Instant.now();
        request.setStatus(GroupJoinRequestStatus.APPROVED);
        request.setProcessedTime(time);

        GroupJoinRequestEvent event = new GroupJoinRequestEvent(GroupJoinRequestEventType.APPROVED, request, user, time);
        groupJoinRequestEventRepository.save(event);
    }

    @Override
    @Transactional
    public void decline(String userUid, String requestUid) {
        User user = userRepository.findOneByUid(userUid);
        GroupJoinRequest request = groupJoinRequestRepository.findOneByUid(requestUid);

        logger.info("Declining request: request={}, user={}", request, user);
        request.setStatus(GroupJoinRequestStatus.DECLINED);
        Instant time = Instant.now();
        request.setProcessedTime(time);

        GroupJoinRequestEvent event = new GroupJoinRequestEvent(GroupJoinRequestEventType.DECLINED, request, user, time);
        groupJoinRequestEventRepository.save(event);
    }
}
