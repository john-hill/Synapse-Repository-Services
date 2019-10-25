package org.sagebionetworks.repo.model.table;

import org.sagebionetworks.repo.model.annotation.v2.AnnotationsValueType;

/**
 * 
 * Enumeration of the currently supported annotation types.
 *
 */
public enum AnnotationType{
	STRING	(ColumnType.STRING, ColumnType.STRING_LIST, AnnotationsValueType.STRING),
	LONG	(ColumnType.INTEGER, ColumnType.INTEGER_LIST, AnnotationsValueType.LONG),
	DOUBLE	(ColumnType.DOUBLE, ColumnType.DOUBLE_LIST, AnnotationsValueType.DOUBLE),
	DATE	(ColumnType.DATE, ColumnType.DATE_LIST, AnnotationsValueType.TIMESTAMP_MS);
	
	AnnotationType(ColumnType columnType, ColumnType listColumnType,AnnotationsValueType annotationsV2ValueType){
		this.columnType = columnType;
		this.listColumnType = listColumnType;
		this.annotationsV2ValueType = annotationsV2ValueType;
	}

	ColumnType columnType;
	ColumnType listColumnType;
	AnnotationsValueType annotationsV2ValueType;

	/**
	 * Get the column type mapped to this annotation type.
	 * @return
	 */
	public ColumnType getColumnType(){
		return columnType;
	}

	public ColumnType getListColumnType(){
		return listColumnType;
	}

	public AnnotationsValueType getAnnotationsV2ValueType() {
		return annotationsV2ValueType;
	}

	public static AnnotationType forAnnotationV2Type(AnnotationsValueType v2ValueType){
		for(AnnotationType annotationType: values()){
			if (annotationType.annotationsV2ValueType == v2ValueType){
				return annotationType;
			}
		}
		throw new IllegalArgumentException("unexpected AnnotationsV2ValueType");
	}
}