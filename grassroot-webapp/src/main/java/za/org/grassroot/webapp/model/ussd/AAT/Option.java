//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.11 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2015.07.23 at 01:56:30 AM SAST 
//


package za.org.grassroot.webapp.model.ussd.AAT;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlValue;
import java.net.URI;

@XmlAccessorType(XmlAccessType.FIELD)
public class Option {

    @XmlValue
    final public String value;

    @XmlAttribute
    final public int command;

    @XmlAttribute
    final public int order;

    @XmlAttribute
    final public URI callback;

    @XmlAttribute
    final public Boolean display;

    //well i had to, i was get a number of error one of which was  default consructor not found
    //I also had to expose all the fields publicly, will revert in due time
    public Option() {
        value = null;
        order = 1;
        callback = null;
        display = null;
        command = 1;
    }

    public Option(String value, int command, int order, URI callback, Boolean display) {
        this.value = value;
        this.command = command;
        this.order = order;
        this.callback = callback;
        this.display = display;
    }
}
