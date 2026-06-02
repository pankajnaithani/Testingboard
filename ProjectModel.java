package com.whiteboard.cleanrecord;

public class ProjectModel {
    private long id;
    private String name;
    private long lastModified;
    private String jsonContent; // Stores serialized stroke vectors for all slides

    public ProjectModel(long id, String name, long lastModified, String jsonContent) {
        this.id = id;
        this.name = name;
        this.lastModified = lastModified;
        this.jsonContent = jsonContent;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public long getLastModified() { return lastModified; }
    public String getJsonContent() { return jsonContent; }

    public void setName(String name) { this.name = name; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }
    public void setJsonContent(String jsonContent) { this.jsonContent = jsonContent; }
}
