package za.org.grassroot.services;

import za.org.grassroot.core.domain.Event;
import za.org.grassroot.core.domain.User;

import java.util.Map;

/**
 * Created by aakilomar on 8/24/15.
 */
public interface MeetingNotificationService {

    public String createMeetingNotificationMessage(User user, Event event);
    public String createChangeMeetingNotificationMessage(User user, Event event);
    public String createCancelMeetingNotificationMessage(User user, Event event);

}
