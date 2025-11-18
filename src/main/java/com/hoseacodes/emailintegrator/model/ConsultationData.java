package com.hoseacodes.emailintegrator.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ConsultationData {
    private String firstName;
    private String lastName;
    private String email;
    private String company;
    private String consultationType;
    private String date;
    private String timeSlot;
    private String meetingLink;
    private String phone;
    private String notes;
    
    public ConsultationData() {
    }
    
    public ConsultationData(String firstName, String lastName, String email, String company, 
                           String consultationType, String date, String timeSlot, String meetingLink) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.company = company;
        this.consultationType = consultationType;
        this.date = date;
        this.timeSlot = timeSlot;
        this.meetingLink = meetingLink;
    }
    
    public ConsultationData(String firstName, String lastName, String email, String company, 
                           String consultationType, String date, String timeSlot, String meetingLink,
                           String phone, String notes) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.company = company;
        this.consultationType = consultationType;
        this.date = date;
        this.timeSlot = timeSlot;
        this.meetingLink = meetingLink;
        this.phone = phone;
        this.notes = notes;
    }
    
    // Getters and Setters
    public String getFirstName() {
        return firstName;
    }
    
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }
    
    public String getLastName() {
        return lastName;
    }
    
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }
    
    public String getEmail() {
        return email;
    }
    
    public void setEmail(String email) {
        this.email = email;
    }
    
    public String getCompany() {
        return company;
    }
    
    public void setCompany(String company) {
        this.company = company;
    }
    
    public String getConsultationType() {
        return consultationType;
    }
    
    public void setConsultationType(String consultationType) {
        this.consultationType = consultationType;
    }
    
    public String getDate() {
        return date;
    }
    
    public void setDate(String date) {
        this.date = date;
    }
    
    public String getTimeSlot() {
        return timeSlot;
    }
    
    public void setTimeSlot(String timeSlot) {
        this.timeSlot = timeSlot;
    }
    
    public String getMeetingLink() {
        return meetingLink;
    }
    
    public void setMeetingLink(String meetingLink) {
        this.meetingLink = meetingLink;
    }
    
    public String getPhone() {
        return phone;
    }
    
    public void setPhone(String phone) {
        this.phone = phone;
    }
    
    public String getNotes() {
        return notes;
    }
    
    public void setNotes(String notes) {
        this.notes = notes;
    }
    
    // Utility methods for date formatting
    public String getFormattedDate() {
        try {
            LocalDate localDate = LocalDate.parse(date);
            return localDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"));
        } catch (Exception e) {
            return date; // fallback to original format
        }
    }
    
    public String getFormattedTime() {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(date + "T" + timeSlot + ":00");
            return dateTime.format(DateTimeFormatter.ofPattern("h:mm a")) + " CST";
        } catch (Exception e) {
            return timeSlot; // fallback to original format
        }
    }
    
    public String getFullName() {
        return firstName + " " + lastName;
    }
    
    // Generate calendar event content (ICS format)
    public String generateCalendarEvent() {
        try {
            LocalDateTime startDateTime = LocalDateTime.parse(date + "T" + timeSlot + ":00");
            LocalDateTime endDateTime = startDateTime.plusMinutes(30); // 30-minute consultation
            
            String uid = "consultation-" + System.currentTimeMillis() + "@ambitiousconcept.com";
            String dtStamp = formatICSDate(LocalDateTime.now());
            String dtStart = formatICSDate(startDateTime);
            String dtEnd = formatICSDate(endDateTime);
            
            StringBuilder icsContent = new StringBuilder();
            icsContent.append("BEGIN:VCALENDAR\r\n");
            icsContent.append("VERSION:2.0\r\n");
            icsContent.append("PRODID:-//Ambitious Concepts//Engineering Consultation//EN\r\n");
            icsContent.append("CALSCALE:GREGORIAN\r\n");
            icsContent.append("METHOD:REQUEST\r\n");
            icsContent.append("BEGIN:VEVENT\r\n");
            icsContent.append("UID:").append(uid).append("\r\n");
            icsContent.append("DTSTAMP:").append(dtStamp).append("\r\n");
            icsContent.append("DTSTART:").append(dtStart).append("\r\n");
            icsContent.append("DTEND:").append(dtEnd).append("\r\n");
            icsContent.append("SUMMARY:Engineering Consultation with ").append(company).append("\r\n");
            icsContent.append("DESCRIPTION:Engineering consultation with ").append(getFullName())
                      .append(" from ").append(company).append(".\\n\\n")
                      .append("Consultation Type: ").append(consultationType).append("\\n\\n")
                      .append("Meeting Link: ").append(meetingLink);
            
            if (notes != null && !notes.trim().isEmpty()) {
                icsContent.append("\\n\\nNotes: ").append(notes);
            }
            
            icsContent.append("\r\n");
            icsContent.append("LOCATION:").append(meetingLink).append("\r\n");
            icsContent.append("ORGANIZER;CN=Ambitious Concepts:mailto:info@ambitiousconcept.com\r\n");
            icsContent.append("ATTENDEE;CN=").append(getFullName())
                      .append(";RSVP=TRUE:mailto:").append(email).append("\r\n");
            icsContent.append("STATUS:CONFIRMED\r\n");
            icsContent.append("TRANSP:OPAQUE\r\n");
            icsContent.append("END:VEVENT\r\n");
            icsContent.append("END:VCALENDAR\r\n");
            
            return icsContent.toString();
            
        } catch (Exception e) {
            return ""; // Return empty string if calendar generation fails
        }
    }
    
    private String formatICSDate(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
    }
}
