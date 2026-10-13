package uk.gov.defra.trade.imports.animals.template;

/** Where a template is in its life: still being created, or saved. */
public enum TemplateStatus {

    /** The template has been named but not yet saved, so Manage templates does not list it. */
    IN_PROGRESS,

    /** The template has been saved and Manage templates lists it. */
    SAVED
}
