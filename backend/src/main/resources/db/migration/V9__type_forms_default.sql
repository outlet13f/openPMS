-- S28: types.attribute_groups is now the type's form ([{name, attributes}…, {hidden: […]}], '[]' = the default).
-- The seed filled it with an early sketch (snake_case keys, never read by anything); start every type from the default.
update types set attribute_groups = '[]';
