package org.eclipse.rdf4j.AST;


import org.eclipse.rdf4j.plan.PlanNode;
import org.eclipse.rdf4j.validation.ShaclSailConnection;

/**
 * Created by heshanjayasinghe on 7/11/17.
 */
public interface PlanGenerator {
    PlanNode getPlan(ShaclSailConnection shaclSailConnection, Shape shape);
}
