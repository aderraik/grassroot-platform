package za.org.grassroot.services;

import za.org.grassroot.core.domain.Address;

/**
 * Created by paballo on 2016/07/14.
 */
public interface AddressBroker {

    Address getUserAddress(String userUid);

    void adduserAddress(String userUid, String houseNumber, String street, String town);

    void updateUserAddress(String userUid, String houseNumber, String street, String town);

    void updateHouseNumber(String userUid, String houseNumber);

    void updateStreet(String userUid, String street);

    void updateTown(String userUid, String town);

    void removeAddress(String userUid);


    boolean addressExists(String userUid);


}
