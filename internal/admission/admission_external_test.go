package admission_test

import (
	"reflect"
	"testing"

	"github.com/leugenea/qmix/internal/admission"
)

func TestErrorHasNoExportedMutableFields(t *testing.T) {
	typeOfError := reflect.TypeOf(admission.Error{})
	for i := 0; i < typeOfError.NumField(); i++ {
		field := typeOfError.Field(i)
		if field.IsExported() {
			t.Fatalf("admission.Error field %q is exported and caller-mutable", field.Name)
		}
	}
}
